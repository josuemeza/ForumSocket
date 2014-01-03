/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package Model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.Collection;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Josue
 */
public class Channel {
    
    /* 
     * Singleton object
     */
    private static Channel channel = null;
    
    /* 
     * Attributes
     */
    private MulticastSocket socket;
    private InetAddress groupAddress;
    private int port;
    private int bufferLength;
    private int confirmationTime;
    private int timeToLive;
    private LinkedList<Moderator> localModerators;
    private LinkedList<ParticipantNode> localParticipants;
    private ParticipantListHandler participantHandler;
    
    /*
     * Threads
     */
    private MessageReciever reciever;
    private NamesConfirmer confirmer;
    
    /*
     * Constants
     */
    private final String CONFIRMATION_MESSAGE = "_CONFIRMATION";
    
    /* 
     * Constructors
     */
    private Channel(InetAddress groupAddress, int port, int bufferLength, int confirmationTime, int timeToLive) throws IOException {
        this.groupAddress = groupAddress;
        this.port = port;
        this.bufferLength = bufferLength;
        this.confirmationTime = confirmationTime;
        this.timeToLive = timeToLive;
        this.localModerators = new LinkedList<Moderator>();
        this.localParticipants = new LinkedList<ParticipantNode>();
        this.participantHandler = new ParticipantListHandler();
        this.socket = new MulticastSocket(port);
        this.socket.joinGroup(groupAddress);
        this.confirmer = new NamesConfirmer();
        this.reciever = new MessageReciever();
        this.confirmer.start();
        this.reciever.start();
        this.participantHandler.start();
    }
    
    /* 
     * Instantiation methods
     */
    public static Channel getInstance(InetAddress groupAddress, int port, int bufferLength, int confirmationTime, int timeToLive) throws ChannelException, IOException {
        // Validations
        if(channel!=null) throw new ChannelException("The instance has already been created.");
        if(!groupAddress.isMulticastAddress()) throw new ChannelException("Address out of range. Use address between 224.0.0.0 to 239.255.255.255.");
        if(port<1024 || port>65535) throw new ChannelException("Port out of range. Use port between 1024 to 65535.");
        if(bufferLength<0) throw new ChannelException("Buffer length must be positive.");
        if(confirmationTime<0) throw new ChannelException("Confirmation time must be positive.");
        if(timeToLive<0) throw new ChannelException("Time to live must be positive.");
        // Create channel
        channel = new Channel(groupAddress, port, bufferLength, confirmationTime, timeToLive);
        return channel;
    }
    
    public static Channel getInstance() throws ChannelException {
        if(channel==null) throw new ChannelException("The instance has not been created.");
        return channel;
    }
    
    /*
     * Send methods
     */
    private synchronized void sendMessage(String sender, Collection<String> recivers, Serializable message) throws IOException {
        ForumMessage forumMessage = new ForumMessage(sender, recivers, message);
        DatagramPacket datagram = forumMessageToDatagramPacket(forumMessage);
        this.socket.send(datagram);
    }
    
    public synchronized void sendMessage(Participant sender, Collection<String> recivers, Serializable message) throws ChannelException, IOException {
        if(this.localParticipants.indexOf(new ParticipantNode(sender, 0))==-1) throw new ChannelException("Participant don't joined group.");
        this.sendMessage(sender.getParticipantName(), recivers, message);
    }
    
    public synchronized void sendMessage(Participant sender, Serializable message) throws ChannelException, IOException {
        if(this.localParticipants.indexOf(new ParticipantNode(sender, 0))==-1) throw new ChannelException("Participant don't joined group.");
        this.sendMessage(sender, null, message);
    }
    
    /*
     * Join group
     */
    public void addModerator(Moderator moderator) throws ChannelException {
        if(moderator==null) throw new ChannelException("Moderator can not be null.");
        this.localModerators.add(moderator);
    }
    
    public void joinGroup(Participant participant) throws ChannelException {
        // Validations
        if(participant==null) throw new ChannelException("Participant can not be null.");
        if(participant.getParticipantName().equals("")) throw new ChannelException("Participant name is required.");
        if(participant.getParticipantName().charAt(0)=='_') throw new ChannelException("Participant name can not be start with '_'");
        if(this.participantHandler.indexOf(participant)!=-1) throw new ChannelException("Name already used.");
        // Join group
        ParticipantNode node = new ParticipantNode(participant, this.timeToLive);
        this.localParticipants.add(node);
        this.participantHandler.add(participant);
    }
    
    /*
     * Leave group
     */
    public void leaveGroup(Participant participant) throws ChannelException {
        int index = -1;
        for(int i=0; i<this.localParticipants.size(); i++) {
            if(this.localParticipants.get(i).participant.getParticipantName().equals(participant.getParticipantName())) {
                index = i;
                break;
            }
        }
        if(index==-1) throw new ChannelException("Participant not found.");
        this.localParticipants.remove(index);
        this.participantHandler.remove(participant);
    }
    
    /*
     * Converters
     */
    private DatagramPacket forumMessageToDatagramPacket(ForumMessage message) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(message);
        oos.close();
        byte[] buffer = baos.toByteArray();
        return new DatagramPacket(buffer, buffer.length, this.groupAddress, this.port);
    }
    
    private ForumMessage datagramPacketToForumMessage(DatagramPacket datagram) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream(datagram.getData());
        ObjectInputStream ois = new ObjectInputStream(bais);
        ForumMessage message = (ForumMessage)ois.readObject();
        ois.close();
        return message;
    }
    
    /*
     * Reciever listener
     */
    class MessageReciever extends Thread {
        
        private synchronized void notify(ForumMessage message) {
            if(message.getRecieverNames()==null) {
                for(ParticipantNode localParticipant : Channel.this.localParticipants) {
                    System.out.println("Incoming message: For all");
                    localParticipant.participant.recieveMessage(message);
                }
            } else {
                for(String targetName : message.getRecieverNames()) {
                    for(ParticipantNode localParticipant : Channel.this.localParticipants) {
                        if(targetName.equals(localParticipant.participant.getParticipantName())) {
                    System.out.println("Incoming message: Personal");
                            localParticipant.participant.recieveMessage(message);
                        }
                    }
                }
            }
        }
        
        @Override
        public void run() {
            byte[] buffer = new byte[Channel.this.bufferLength];
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            while(true) {
                try {
                    socket.receive(datagram);
                    ForumMessage message = datagramPacketToForumMessage(datagram);
                    if(message.getSenderName().equals(Channel.this.CONFIRMATION_MESSAGE)) {
                        // Participant confirmation
                        LinkedList<String> confirmedParticipants = (LinkedList<String>)message.getMessage();
                        for(String confirmedName : confirmedParticipants) {
                            ParticipantTemp temp = new ParticipantTemp(confirmedName);
                            if(!Channel.this.participantHandler.ttlIncrement(temp)) {
                                Channel.this.participantHandler.add(temp);
                            }
                        }
                    } else {
                        // User message
                        notify(message);
                    }
                } catch (IOException ex) {
                    Logger.getLogger(Channel.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ClassNotFoundException ex) {
                    Logger.getLogger(Channel.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        
    }
    
    /*
     * Names confirmer thread
     */
    class NamesConfirmer extends Thread {
        
        public synchronized void confirm() {
            try {
                LinkedList<String> localNames = new LinkedList<String>();
                for(ParticipantNode localParticipant : Channel.this.localParticipants) {
                    localNames.add(localParticipant.participant.getParticipantName());
                }
                Channel.this.sendMessage(CONFIRMATION_MESSAGE, null, localNames);
            } catch (IOException ex) {
                Logger.getLogger(Channel.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
        @Override
        public void run() {
            while(true) {
                try {
                    this.sleep(Channel.this.confirmationTime);
                    this.confirm();
                } catch (InterruptedException ex) {
                    Logger.getLogger(Channel.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        
    }
    
    class ParticipantListHandler extends Thread {
        
        /*
         * Constants
         */
        private final int ADD = 0;
        private final int PUT = 1;
        private final int REMOVE = 2;
        private final int INCREMENT = 3;
        private final int REDUCE = 4;
        private final int GET_COUNTER = 5;
        private final int INDEX_OF = 6;
        private final int SUCCESS = -1;
        private final int ERROR = -2;
        
        /*
         * Atributes
         */
        private LinkedList<ParticipantNode> participants;
        
        public ParticipantListHandler() {
            this.participants = new LinkedList<ParticipantNode>();
        }
        
        private synchronized int listHandler(int action, Participant participant, int value) {
            switch(action) {
                case ADD:
                    this.participants.add(new ParticipantNode(participant, Channel.this.timeToLive));
                    for(Moderator m : Channel.this.localModerators) {
                        m.participantRequest(participant.getParticipantName());
                    }
                    System.out.println("Added " + participant.getParticipantName());
                    return SUCCESS;
                case PUT:
                    for(ParticipantNode node : this.participants) {
                        if(node.participant.getParticipantName().equals(participant.getParticipantName())) {
                            node.counter = value;
                            return SUCCESS;
                        }
                    }
                    break;
                case REMOVE:
                    for(int i=0; i<this.participants.size(); i++) {
                        if(this.participants.get(i).participant.equals(participant.getParticipantName())) {
                            for(Moderator m : Channel.this.localModerators) {
                                m.participantWithdrawing(this.participants.get(i).participant.getParticipantName());
                            }
                            this.participants.remove(i);
                        }
                    }
                    System.out.println("Removed " + participant.getParticipantName());
                    break;
                case INCREMENT:
                    for(ParticipantNode node : this.participants) {
                        if(node.participant.getParticipantName().equals(participant.getParticipantName())) {
                            node.counter++;
                            return SUCCESS;
                        }
                    }
                    break;
                case REDUCE:
                    for(int i=0; i<this.participants.size(); i++) {
                        this.participants.get(i).counter--;
                        if(this.participants.get(i).counter==0) {
                            for(Moderator m : Channel.this.localModerators) {
                                m.participantWithdrawing(this.participants.get(i).participant.getParticipantName());
                            }
                            this.participants.remove(i);
                        }
                    }
                    return SUCCESS;
                case GET_COUNTER:
                    for(ParticipantNode node : this.participants) {
                        if(node.participant.getParticipantName().equals(participant.getParticipantName())) {
                            return node.counter;
                        }
                    }
                    break;
                case INDEX_OF:
                    for(int i=0; i<this.participants.size(); i++) {
                        if(this.participants.get(i).participant.equals(participant.getParticipantName())) {
                            return i;
                        }
                    }
                    return -1;
            }
            return ERROR;
        }
        
        public int indexOf(Participant participant) {
            return this.listHandler(INDEX_OF, participant, 0);
        }
        
        public void add(Participant participant) {
            this.listHandler(ADD, participant, 0);
        }
        
        public void remove(Participant participant) {
            this.listHandler(REMOVE, participant, 0);
        }
        
        public boolean ttlIncrement(Participant participant) {
            return this.listHandler(INCREMENT, participant, 0)==SUCCESS;
        }
        
        
        @Override
        public void run() {
            while(true) {
                try {
                    this.sleep(Channel.this.confirmationTime/2);
                    while(true) {
                        this.sleep(Channel.this.confirmationTime);
                        this.listHandler(REDUCE, null, 0);
                    }
                } catch (InterruptedException ex) {
                    Logger.getLogger(Channel.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        
    }
    
    /*
     * Participant node
     */
    class ParticipantNode {
        
        public Participant participant;
        public int counter;
        
        public ParticipantNode(Participant participant, int counter) {
            this.participant = participant;
            this.counter = counter;
        }
        
        @Override
        public boolean equals(Object node) {
            if(node==null) return false;
            if(node==this) return true;
            if(!(node instanceof ParticipantNode)) return false;
            ParticipantNode n = (ParticipantNode)node;
            return participant.getParticipantName().equals(n.participant.getParticipantName());
        }
        
    }
    
    class ParticipantTemp implements Participant {

        private String name;
        
        public ParticipantTemp(String name) {
            this.name = name;
        }
        
        @Override
        public void recieveMessage(ForumMessage message) {}

        @Override
        public String getParticipantName() {
             return this.name;
        }
        
    }
    
}

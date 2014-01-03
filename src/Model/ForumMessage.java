/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package Model;

import java.io.Serializable;
import java.util.Collection;

/**
 *
 * @author Josue
 */
public class ForumMessage implements Serializable {
    
    private String senderName;
    private Collection<String> recieverNames;
    private Object message;
    
    public ForumMessage(String senderName, Collection<String> reciverNames, Object message) {
        this.senderName = senderName;
        this.recieverNames = reciverNames;
        this.message = message;
    }

    public String getSenderName() {
        return senderName;
    }

    public Collection<String> getRecieverNames() {
        return recieverNames;
    }

    public Object getMessage() {
        return message;
    }
    
}

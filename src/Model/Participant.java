/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package Model;

/**
 *
 * @author Josue
 */
public interface Participant {
    
    public void recieveMessage(ForumMessage message);
    public String getParticipantName();
    
}

package com.dqms.model;

import java.io.Serializable;
import java.util.List;

/**
 * Network message envelope. All TCP communication between nodes uses this class.
 */
public class Message implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Type {
        NEW_TICKET,
        UPDATE_STATUS,
        SYNC_REQUEST,
        SYNC_RESPONSE
    }

    private final Type type;
    private final String senderNodeId;
    private final boolean senderAdmin;
    private final int senderTcpPort; // DYNAMIC: Tell receiver our port for two-way TCP

    // Payload fields
    private Ticket ticket;
    private String ticketId;
    private String newStatus;
    private List<Ticket> ticketList;

    private Message(Type type, String senderNodeId, boolean senderAdmin, int senderTcpPort) {
        this.type = type;
        this.senderNodeId = senderNodeId;
        this.senderAdmin = senderAdmin;
        this.senderTcpPort = senderTcpPort;
    }

    public static Message newTicket(String senderNodeId, boolean isAdmin, int tcpPort, Ticket ticket) {
        Message m = new Message(Type.NEW_TICKET, senderNodeId, isAdmin, tcpPort);
        m.ticket = ticket;
        return m;
    }

    public static Message updateStatus(String senderNodeId, boolean isAdmin, int tcpPort, String ticketId, String newStatus) {
        Message m = new Message(Type.UPDATE_STATUS, senderNodeId, isAdmin, tcpPort);
        m.ticketId = ticketId;
        m.newStatus = newStatus;
        return m;
    }

    public static Message syncRequest(String senderNodeId, int tcpPort, boolean isAdmin) {
        return new Message(Type.SYNC_REQUEST, senderNodeId, isAdmin, tcpPort);
    }

    public static Message syncResponse(String senderNodeId, boolean isAdmin, int tcpPort, List<Ticket> tickets) {
        Message m = new Message(Type.SYNC_RESPONSE, senderNodeId, isAdmin, tcpPort);
        m.ticketList = tickets;
        return m;
    }

    public Type    getType()          { return type; }
    public String  getSenderNodeId()  { return senderNodeId; }
    public boolean isSenderAdmin()    { return senderAdmin; }
    public int     getSenderTcpPort() { return senderTcpPort; }
    public Ticket  getTicket()        { return ticket; }
    public String  getTicketId()      { return ticketId; }
    public String  getNewStatus()     { return newStatus; }
    public List<Ticket> getTicketList() { return ticketList; }
}

package com.dqms.model;

import java.io.Serializable;
import java.util.List;

/**
 * Network message envelope. All TCP communication between nodes uses this class.
 *
 * Message types:
 *   NEW_TICKET    — a new student joined the queue (carries one Ticket)
 *   UPDATE_STATUS — a student was cleared        (carries ticketId + new status)
 *   SYNC_REQUEST  — new node asking for full queue state (no payload)
 *   SYNC_RESPONSE — response to SYNC_REQUEST     (carries full ticket list)
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

    // Payload fields — only one is used depending on type
    private Ticket ticket;              // NEW_TICKET
    private String ticketId;           // UPDATE_STATUS
    private String newStatus;          // UPDATE_STATUS
    private List<Ticket> ticketList;   // SYNC_RESPONSE

    // ── Constructors ─────────────────────────────────────────────────────────

    /** NEW_TICKET */
    public static Message newTicket(String senderNodeId, Ticket ticket) {
        Message m = new Message(Type.NEW_TICKET, senderNodeId);
        m.ticket = ticket;
        return m;
    }

    /** UPDATE_STATUS */
    public static Message updateStatus(String senderNodeId, String ticketId, String newStatus) {
        Message m = new Message(Type.UPDATE_STATUS, senderNodeId);
        m.ticketId = ticketId;
        m.newStatus = newStatus;
        return m;
    }

    /** SYNC_REQUEST */
    public static Message syncRequest(String senderNodeId) {
        return new Message(Type.SYNC_REQUEST, senderNodeId);
    }

    /** SYNC_RESPONSE */
    public static Message syncResponse(String senderNodeId, List<Ticket> tickets) {
        Message m = new Message(Type.SYNC_RESPONSE, senderNodeId);
        m.ticketList = tickets;
        return m;
    }

    private Message(Type type, String senderNodeId) {
        this.type = type;
        this.senderNodeId = senderNodeId;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public Type   getType()          { return type; }
    public String getSenderNodeId()  { return senderNodeId; }
    public Ticket getTicket()        { return ticket; }
    public String getTicketId()      { return ticketId; }
    public String getNewStatus()     { return newStatus; }
    public List<Ticket> getTicketList() { return ticketList; }
}

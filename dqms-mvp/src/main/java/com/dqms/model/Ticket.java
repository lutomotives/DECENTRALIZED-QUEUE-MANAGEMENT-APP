package com.dqms.model;

import java.io.Serializable;

/**
 * Core data entity representing a student's position in the queue.
 * Must be Serializable so it can be sent over TCP ObjectOutputStream.
 */
public class Ticket implements Serializable, Comparable<Ticket> {

    private static final long serialVersionUID = 1L;

    private final String ticketId;          // registrationNumber + "_" + issueTime
    private final String registrationNumber;
    private final String studentName;
    private final long issueTime;           // System.currentTimeMillis() at creation
    private final String originNodeId;      // which node created this ticket
    private volatile String status;         // "WAITING" or "CLEARED"

    public Ticket(String registrationNumber, String studentName, long issueTime, String originNodeId) {
        this.registrationNumber = registrationNumber;
        this.studentName = studentName;
        this.issueTime = issueTime;
        this.originNodeId = originNodeId;
        this.ticketId = registrationNumber + "_" + issueTime;
        this.status = "WAITING";
    }

    /**
     * FIFO ordering:
     *   1. Earlier issueTime = higher priority
     *   2. Tie-break: lexicographic comparison of registrationNumber
     */
    @Override
    public int compareTo(Ticket other) {
        if (this.issueTime != other.issueTime) {
            return Long.compare(this.issueTime, other.issueTime);
        }
        return this.registrationNumber.compareTo(other.registrationNumber);
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public String getTicketId()          { return ticketId; }
    public String getRegistrationNumber() { return registrationNumber; }
    public String getStudentName()       { return studentName; }
    public long   getIssueTime()         { return issueTime; }
    public String getOriginNodeId()      { return originNodeId; }
    public String getStatus()            { return status; }
    public void   setStatus(String s)    { this.status = s; }

    @Override
    public String toString() {
        return String.format("[%s | %s | %s | %s | %s]",
                ticketId, studentName, registrationNumber, originNodeId, status);
    }
}
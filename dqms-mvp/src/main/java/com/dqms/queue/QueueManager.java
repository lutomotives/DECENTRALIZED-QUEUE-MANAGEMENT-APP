package com.dqms.queue;

import com.dqms.db.DatabaseManager;
import com.dqms.model.Message;
import com.dqms.model.NodeInfo;
import com.dqms.model.Ticket;
import com.dqms.network.TCPClient;

import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.logging.Logger;

/**
 * Central coordinator for all queue operations.
 *
 * Responsibilities:
 *   - Maintain in-memory PriorityBlockingQueue (thread-safe, FIFO-ordered)
 *   - Persist every change to local SQLite via DatabaseManager
 *   - Broadcast changes to all known peers via TCPClient
 *   - Handle incoming replicated events from peers
 *
 * Thread safety: all public mutating methods are synchronized on `this`
 * to prevent race conditions when network threads and UI threads both write.
 */
public class QueueManager {

    private static final Logger LOG = Logger.getLogger(QueueManager.class.getName());

    private final String nodeId;
    private final DatabaseManager db;
    private final TCPClient client;
    private final Map<String, NodeInfo> peers; // shared with UDPDiscovery

    // Thread-safe priority queue using our Ticket.compareTo() for FIFO ordering
    private final PriorityBlockingQueue<Ticket> queue = new PriorityBlockingQueue<>();

    // UI refresh callback — set by the JavaFX controller
    private Runnable onQueueChanged;

    public QueueManager(String nodeId, DatabaseManager db, TCPClient client,
                        Map<String, NodeInfo> peers) {
        this.nodeId = nodeId;
        this.db     = db;
        this.client = client;
        this.peers  = peers;
    }

    public synchronized void setOnQueueChanged(Runnable callback) {
        this.onQueueChanged = callback;
    }

    /**
     * @return true if this node is the designated Admin (NODE_001)
     */
    public boolean isAdmin() {
        return "NODE_001".equalsIgnoreCase(nodeId);
    }

    // ── LOCAL OPERATIONS (triggered by this node's UI) ────────────────────────

    /**
     * Student joins the queue from THIS node.
     * Creates ticket, persists locally, broadcasts to all peers.
     */
    public synchronized Ticket createTicket(String registrationNumber, String studentName) {
        long now = System.currentTimeMillis();
        Ticket ticket = new Ticket(registrationNumber, studentName, now, nodeId);

        queue.add(ticket);
        db.insertTicket(ticket);

        // Broadcast to all known peers
        Message msg = Message.newTicket(nodeId, ticket);
        client.broadcast(peers.values(), msg);

        LOG.info("Created ticket: " + ticket);
        notifyUIChanged();
        return ticket;
    }

    /**
     * Admin officer clears a student from THIS node's dashboard.
     * Updates locally and broadcasts to peers.
     */
    public synchronized void clearStudent(String ticketId) {
        // Find ticket to check origin
        Ticket ticket = queue.stream()
                .filter(t -> t.getTicketId().equals(ticketId))
                .findFirst()
                .orElse(null);

        if (ticket == null) return;

        // Restriction: Only Admin (NODE_001) can clear tickets created by OTHER nodes.
        // Regular nodes can only clear tickets they created themselves.
        if (!isAdmin() && !ticket.getOriginNodeId().equalsIgnoreCase(nodeId)) {
            LOG.warning("Unauthorized clear attempt by " + nodeId + " on ticket from " + ticket.getOriginNodeId());
            return;
        }

        queue.removeIf(t -> t.getTicketId().equals(ticketId));
        db.updateStatus(ticketId, "CLEARED");

        // Re-add as CLEARED so it stays visible in the list
        db.getAllTickets().stream()
           .filter(t -> t.getTicketId().equals(ticketId))
           .findFirst()
           .ifPresent(t -> {
               t.setStatus("CLEARED");
               queue.add(t);
           });

        // Broadcast update
        Message msg = Message.updateStatus(nodeId, ticketId, "CLEARED");
        client.broadcast(peers.values(), msg);

        LOG.info("Cleared ticket: " + ticketId);
        notifyUIChanged();
    }

    // ── INCOMING EVENTS (from peer nodes via TCPServer → MessageHandler) ──────

    /**
     * A peer sent us a NEW_TICKET. Insert only if we don't already have it.
     */
    public synchronized void receiveTicket(Ticket ticket) {
        if (db.ticketExists(ticket.getTicketId())) {
            LOG.fine("Duplicate ticket ignored: " + ticket.getTicketId());
            return;
        }
        queue.add(ticket);
        db.insertTicket(ticket);
        LOG.info("Received ticket from peer: " + ticket);
        notifyUIChanged();
    }

    /**
     * A peer sent an UPDATE_STATUS for a ticket.
     */
    public synchronized void receiveStatusUpdate(String ticketId, String newStatus) {
        queue.removeIf(t -> t.getTicketId().equals(ticketId));
        db.updateStatus(ticketId, newStatus);

        // Re-add updated ticket
        db.getAllTickets().stream()
          .filter(t -> t.getTicketId().equals(ticketId))
          .findFirst()
          .ifPresent(queue::add);

        LOG.info("Received status update: " + ticketId + " → " + newStatus);
        notifyUIChanged();
    }

    /**
     * We received a SYNC_RESPONSE from a peer after requesting full state.
     * Rebuild our queue and DB from the peer's data.
     */
    public synchronized void applySyncResponse(List<Ticket> tickets) {
        boolean changed = false;
        for (Ticket t : tickets) {
            if (!db.ticketExists(t.getTicketId())) {
                db.insertTicket(t);
                queue.add(t);
                changed = true;
            } else {
                // If it exists, update status if different (prefer CLEARED over WAITING)
                // This is a simple conflict resolution for late syncs.
                if ("CLEARED".equals(t.getStatus())) {
                    db.updateStatus(t.getTicketId(), "CLEARED");
                    // Update in-memory queue
                    queue.removeIf(q -> q.getTicketId().equals(t.getTicketId()));
                    queue.add(t);
                    changed = true;
                }
            }
        }
        if (changed) {
            LOG.info("Applied sync response: Merged " + tickets.size() + " tickets");
            notifyUIChanged();
        }
    }

    /**
     * Called on startup to load persisted tickets from local SQLite DB.
     * Used when this node is first to start (no peers to sync from).
     */
    public synchronized void loadFromDatabase() {
        List<Ticket> saved = db.getAllTickets();
        queue.addAll(saved);
        LOG.info("Loaded " + saved.size() + " tickets from local DB");
        notifyUIChanged();
    }

    // ── QUERY METHODS (called by UI) ──────────────────────────────────────────

    /**
     * Returns all tickets sorted by FIFO order (WAITING first, then CLEARED).
     */
    public List<Ticket> getAllTicketsAsList() {
        List<Ticket> all = new ArrayList<>(queue);
        all.sort(Comparator.comparingInt((Ticket t) -> t.getStatus().equals("WAITING") ? 0 : 1)
                           .thenComparing(Ticket::compareTo));
        return all;
    }

    /**
     * Returns only WAITING tickets in FIFO order.
     */
    public List<Ticket> getWaitingTickets() {
        return getAllTicketsAsList().stream()
                .filter(t -> "WAITING".equals(t.getStatus()))
                .toList();
    }

    public String getNodeId()                   { return nodeId; }
    public Map<String, NodeInfo> getPeers()     { return Collections.unmodifiableMap(peers); }
    public int getPeerCount()                   { return peers.size(); }

    private void notifyUIChanged() {
        if (onQueueChanged != null) onQueueChanged.run();
    }
}

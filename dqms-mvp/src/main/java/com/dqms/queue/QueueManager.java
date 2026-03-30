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
    private final boolean isAdmin;
    private final int tcpPort;

    // Thread-safe priority queue using our Ticket.compareTo() for FIFO ordering
    private final PriorityBlockingQueue<Ticket> queue = new PriorityBlockingQueue<>();

    // UI refresh callback — set by the JavaFX controller
    private Runnable onQueueChanged;

    public QueueManager(String nodeId, DatabaseManager db, TCPClient client,
                        Map<String, NodeInfo> peers, boolean isAdmin, int tcpPort) {
        this.nodeId = nodeId;
        this.db     = db;
        this.client = client;
        this.peers  = peers;
        this.isAdmin = isAdmin;
        this.tcpPort = tcpPort;
    }

    public void setOnQueueChanged(Runnable callback) {
        this.onQueueChanged = callback;
    }

    public void registerPeer(String nodeId, String ip, int port, boolean isAdmin) {
        peers.put(nodeId, new NodeInfo(nodeId, ip, port));
    }

    // ── LOCAL OPERATIONS (triggered by this node's UI) ────────────────────────

    /**
     * Student joins the queue from THIS node.
     * Creates ticket, persists locally, broadcasts to all peers.
     */
    public synchronized Ticket createTicket(String registrationNumber, String studentName) {
        long now = System.currentTimeMillis();
        Ticket ticket = new Ticket(registrationNumber, studentName, now, nodeId);

        try {
            queue.add(ticket);
            db.insertTicket(ticket);

            // Broadcast to all known peers
            Message msg = Message.newTicket(nodeId, tcpPort, isAdmin, ticket);
            client.broadcast(peers.values(), msg);

            LOG.log(java.util.logging.Level.INFO, "Created ticket: {0}", ticket);
            notifyUIChanged();
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.SEVERE, "Error creating ticket: {0}", e.getMessage());
            queue.removeIf(t -> t.getTicketId().equals(ticket.getTicketId()));
            throw e;
        }
        return ticket;
    }

    /**
     * Admin officer clears a student from THIS node's dashboard.
     * Updates locally and broadcasts to peers.
     */
    public synchronized void clearStudent(String ticketId) {
        queue.removeIf(t -> t.getTicketId().equals(ticketId));
        db.updateStatus(ticketId, "CLEARED");

        // Re-add as CLEARED so it stays visible in the list
        List<Ticket> all = db.getAllTickets();
        all.stream()
           .filter(t -> t.getTicketId().equals(ticketId))
           .findFirst()
           .ifPresent(t -> {
               t.setStatus("CLEARED");
               queue.add(t);
           });

        // Broadcast update
        Message msg = Message.updateStatus(nodeId, tcpPort, isAdmin, ticketId, "CLEARED");
        client.broadcast(peers.values(), msg);

        LOG.log(java.util.logging.Level.INFO, "Cleared ticket: {0}", ticketId);
        notifyUIChanged();
    }

    // ── INCOMING EVENTS (from peer nodes via TCPServer → MessageHandler) ──────

    /**
     * A peer sent us a NEW_TICKET. Insert only if we don't already have it.
     */
    public synchronized void receiveTicket(Ticket ticket) {
        if (db.ticketExists(ticket.getTicketId())) {
        LOG.log(java.util.logging.Level.FINE, "Duplicate ticket ignored: {0}", ticket.getTicketId());
            return;
        }
        queue.add(ticket);
        db.insertTicket(ticket);
        LOG.log(java.util.logging.Level.INFO, "Received ticket from peer: {0}", ticket);
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

        LOG.log(java.util.logging.Level.INFO, "Received status update: {0} → {1}", new Object[]{ticketId, newStatus});
        notifyUIChanged();
    }

    /**
     * We received a SYNC_RESPONSE from a peer after requesting full state.
     * Rebuild our queue and DB from the peer's data.
     */
    public synchronized void applySyncResponse(List<Ticket> tickets) {
        queue.clear();
        for (Ticket t : tickets) {
            if (!db.ticketExists(t.getTicketId())) {
                db.insertTicket(t);
            }
            queue.add(t);
        }
        LOG.log(java.util.logging.Level.INFO, "Applied sync response: {0} tickets loaded", tickets.size());
        notifyUIChanged();
    }

    /**
     * Called on startup to load persisted tickets from local SQLite DB.
     * Used when this node is first to start (no peers to sync from).
     */
    public synchronized void loadFromDatabase() {
        List<Ticket> saved = db.getAllTickets();
        queue.addAll(saved);
        LOG.log(java.util.logging.Level.INFO, "Loaded {0} tickets from local DB", saved.size());
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
    public boolean isAdmin()                    { return isAdmin; }
    public int getTcpPort()                     { return tcpPort; }

    private void notifyUIChanged() {
        if (onQueueChanged != null) onQueueChanged.run();
    }
}

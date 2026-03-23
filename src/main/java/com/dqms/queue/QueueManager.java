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
 * - Maintain in-memory PriorityBlockingQueue (thread-safe, FIFO-ordered)
 * - Persist every change to local SQLite via DatabaseManager
 * - Broadcast changes to all known peers via TCPClient
 * - Handle incoming replicated events from peers
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
        this.db = db;
        this.client = client;
        this.peers = peers;
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

    /**
     * @return true if this node already has a ticket in 'WAITING' status.
     */
    public boolean hasActiveTicket() {
        return queue.stream()
                .anyMatch(t -> t.getOriginNodeId().equalsIgnoreCase(nodeId)
                        && "WAITING".equals(t.getStatus()));
    }

    // ── LOCAL OPERATIONS (triggered by this node's UI) ────────────────────────

    /**
     * Student joins the queue from THIS node.
     * Enforces one-ticket-per-node constraint.
     */
    public synchronized Ticket createTicket(String registrationNumber, String studentName) {
        if (hasActiveTicket()) {
            LOG.warning("Node " + nodeId + " already has an active ticket. Request ignored.");
            return null;
        }

        long now = System.currentTimeMillis();
        Ticket ticket = new Ticket(registrationNumber, studentName, now, nodeId);

        queue.add(ticket);
        db.insertTicket(ticket);

        // Broadcast new ticket to ALL peers so everyone can see it in real-time
        Message msg = Message.newTicket(nodeId, ticket);
        broadcast(msg);

        LOG.info("Created ticket: " + ticket);
        notifyUIChanged();
        return ticket;
    }

    /**
     * Admin officer clears a student from the queue.
     * ONLY NODE_001 is authorized to clear students.
     */
    public synchronized void clearStudent(String ticketId) {
        Ticket ticket = findTicket(ticketId);
        if (ticket == null)
            return;

        // AUTHENTICATION: Only Admin (NODE_001) can clear a person from the queue
        if (!isAdmin()) {
            LOG.warning("Unauthorized clear attempt by " + nodeId
                    + " on ticket. Only NODE_001 (Admin) can clear students.");
            return;
        }

        // Update in-memory
        ticket.setStatus("CLEARED");
        db.updateStatus(ticketId, "CLEARED");

        // Broadcast status update to ALL peers so everyone sees the cleared status
        Message msg = Message.updateStatus(nodeId, ticketId, "CLEARED");
        broadcast(msg);

        LOG.info("Admin (" + nodeId + ") cleared ticket: " + ticketId);
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
        Ticket ticket = findTicket(ticketId);
        if (ticket != null) {
            ticket.setStatus(newStatus);
            db.updateStatus(ticketId, newStatus);
            LOG.info("Updated ticket " + ticketId + " to " + newStatus);
            notifyUIChanged();
        } else {
            // If we didn't have it (maybe sync issue?), just update DB and it'll show up
            // later if needed
            db.updateStatus(ticketId, newStatus);
            LOG.info("Status update for unknown ticket " + ticketId + " → DB updated.");
        }
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
                if ("CLEARED".equals(t.getStatus())) {
                    Ticket local = findTicket(t.getTicketId());
                    if (local != null && !"CLEARED".equals(local.getStatus())) {
                        local.setStatus("CLEARED");
                        db.updateStatus(t.getTicketId(), "CLEARED");
                        changed = true;
                    }
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
     */
    public synchronized void loadFromDatabase() {
        List<Ticket> saved = db.getAllTickets();
        queue.addAll(saved);
        LOG.info("Loaded " + saved.size() + " tickets from local DB");
        notifyUIChanged();
    }

    /**
     * Returns the full in-memory queue without privacy filtering.
     * Used for internal synchronization between nodes.
     */
    public List<Ticket> getUnfilteredTickets() {
        return new ArrayList<>(queue);
    }

    /**
     * Returns all tickets VISIBLE to this node, sorted by status and FIFO.
     * All nodes now see all tickets.
     */
    public List<Ticket> getAllTicketsAsList() {
        List<Ticket> list = new ArrayList<>(queue);

        list.sort(Comparator.comparingInt((Ticket t) -> t.getStatus().equals("WAITING") ? 0 : 1)
                .thenComparing(Ticket::compareTo));
        return list;
    }

    /**
     * Returns only WAITING tickets VISIBLE to this node in FIFO order.
     */
    public List<Ticket> getWaitingTickets() {
        return getAllTicketsAsList().stream()
                .filter(t -> "WAITING".equals(t.getStatus()))
                .toList();
    }

    public String getNodeId() {
        return nodeId;
    }

    public Map<String, NodeInfo> getPeers() {
        return Collections.unmodifiableMap(peers);
    }

    public int getPeerCount() {
        return peers.size();
    }

    private void notifyUIChanged() {
        if (onQueueChanged != null)
            onQueueChanged.run();
    }

    private Ticket findTicket(String ticketId) {
        return queue.stream()
                .filter(t -> t.getTicketId().equals(ticketId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Broadcasts a message to all known peers.
     */
    private void broadcast(Message msg) {
        client.broadcast(peers.values(), msg);
    }

    /**
     * Helper to send a message to a specific node by ID if found in peers.
     */
    private void sendToNode(String targetNodeId, Message msg) {
        if (targetNodeId.equalsIgnoreCase(nodeId))
            return;
        NodeInfo target = peers.get(targetNodeId);
        if (target != null) {
            client.send(target, msg);
        } else {
            LOG.fine("Cannot send to " + targetNodeId + " — node not discovered yet.");
        }
    }
}

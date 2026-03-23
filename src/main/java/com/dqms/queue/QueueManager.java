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
<<<<<<< HEAD
=======
 *
 * Responsibilities:
 * - Maintain in-memory PriorityBlockingQueue (thread-safe, FIFO-ordered)
 * - Persist every change to local SQLite via DatabaseManager
 * - Broadcast changes to all known peers via TCPClient
 * - Handle incoming replicated events from peers
 *
 * Thread safety: all public mutating methods are synchronized on `this`
 * to prevent race conditions when network threads and UI threads both write.
>>>>>>> origin/main
 */
public class QueueManager {

    private static final Logger LOG = Logger.getLogger(QueueManager.class.getName());

    private final String nodeId;
    private final int tcpPort;
    private final DatabaseManager db;
    private final TCPClient client;
    private final Map<String, NodeInfo> peers;
    private final boolean isAdmin;

    private final PriorityBlockingQueue<Ticket> queue = new PriorityBlockingQueue<>();
    private Runnable onQueueChanged;

<<<<<<< HEAD
    public QueueManager(String nodeId, int tcpPort, DatabaseManager db, TCPClient client,
                        Map<String, NodeInfo> peers, boolean isAdmin) {
        this.nodeId  = nodeId;
        this.tcpPort = tcpPort;
        this.db      = db;
        this.client  = client;
        this.peers   = peers;
        this.isAdmin = isAdmin;
        LOG.info(">>> QUEUE MANAGER STARTUP: [" + nodeId + "] | ADMIN ROLE: " + isAdmin);
=======
    public QueueManager(String nodeId, DatabaseManager db, TCPClient client,
            Map<String, NodeInfo> peers) {
        this.nodeId = nodeId;
        this.db = db;
        this.client = client;
        this.peers = peers;
>>>>>>> origin/main
    }

    public synchronized void setOnQueueChanged(Runnable callback) {
        this.onQueueChanged = callback;
    }

    public boolean isAdmin() { return isAdmin; }

    public synchronized void registerPeer(String peerId, String ip, int port, boolean peerIsAdmin) {
        if (peerId.equals(nodeId)) return;
        if (!peers.containsKey(peerId)) {
            NodeInfo peer = new NodeInfo(peerId, ip, port, peerIsAdmin);
            peers.put(peerId, peer);
            LOG.info(">>> PEER CONNECTED: " + peerId);
        }
    }

    /**
     * @return true if this node already has a ticket in 'WAITING' status.
     */
    public boolean hasActiveTicket() {
        synchronized (queue) {
            return queue.stream()
                    .anyMatch(t -> t.getOriginNodeId().equalsIgnoreCase(nodeId)
                            && "WAITING".equals(t.getStatus()));
        }
    }

    public synchronized Ticket createTicket(String registrationNumber, String studentName) {
        if (hasActiveTicket()) {
            LOG.warning("Node " + nodeId + " already has an active ticket. Request ignored.");
            return null;
        }

        Ticket ticket = new Ticket(registrationNumber, studentName, System.currentTimeMillis(), nodeId);
        LOG.info(">>> CREATING TICKET: " + ticket.getTicketId() + " for origin " + nodeId);
        db.insertTicket(ticket);
<<<<<<< HEAD
        loadFromDatabase(); 
        Message msg = Message.newTicket(nodeId, isAdmin, tcpPort, ticket);
        client.broadcast(peers.values(), msg);
        return ticket;
    }

    public synchronized void clearStudent(String ticketId) {
        if (!isAdmin) {
            LOG.warning(">>> REJECTED: Only Admin can clear tickets.");
=======

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
>>>>>>> origin/main
            return;
        }
        LOG.info(">>> ADMIN ACTION: Clearing " + ticketId);
        db.updateStatus(ticketId, "CLEARED");
<<<<<<< HEAD
        loadFromDatabase(); 
        Message msg = Message.updateStatus(nodeId, isAdmin, tcpPort, ticketId, "CLEARED");
        client.broadcast(peers.values(), msg);
=======

        // Broadcast status update to ALL peers so everyone sees the cleared status
        Message msg = Message.updateStatus(nodeId, ticketId, "CLEARED");
        broadcast(msg);

        LOG.info("Admin (" + nodeId + ") cleared ticket: " + ticketId);
        notifyUIChanged();
>>>>>>> origin/main
    }

    public synchronized void receiveTicket(Ticket ticket) {
        if (!db.ticketExists(ticket.getTicketId())) {
            LOG.info(">>> REPLICATING TICKET: " + ticket.getTicketId());
            db.insertTicket(ticket);
            loadFromDatabase();
        }
    }

    public synchronized void receiveStatusUpdate(String ticketId, String newStatus) {
<<<<<<< HEAD
        LOG.info(">>> REPLICATING STATUS: " + ticketId + " -> " + newStatus);
        // If the ticket doesn't exist locally, we still want to request a sync to ensure DB consistency
        if (!db.ticketExists(ticketId)) {
            LOG.warning(">>> MISSING TICKET DURING STATUS UPDATE. Requesting full sync...");
            // We should ideally sync here, but for now we just log.
=======
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
>>>>>>> origin/main
        }
        db.updateStatus(ticketId, newStatus);
        loadFromDatabase();
    }

    public synchronized void applySyncResponse(List<Ticket> tickets) {
        if (tickets == null) return;
        LOG.info(">>> SYNC RECEIVED: Processing " + tickets.size() + " tickets.");
        for (Ticket t : tickets) {
            db.insertTicket(t);
            if ("CLEARED".equals(t.getStatus())) {
                db.updateStatus(t.getTicketId(), "CLEARED");
            }
        }
        loadFromDatabase();
    }

    public synchronized void loadFromDatabase() {
        try {
            List<Ticket> saved = db.getAllTickets();
            synchronized (queue) {
                queue.clear();
                queue.addAll(saved);
            }
            LOG.info(">>> MEMORY RELOAD: Found " + saved.size() + " tickets in DB.");
            for (Ticket t : saved) {
                LOG.fine("    - Ticket: " + t.getTicketId() + " | Origin: " + t.getOriginNodeId());
            }
            notifyUIChanged();
        } catch (Exception e) {
            LOG.severe(">>> ERROR loading from database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public List<Ticket> getUnfilteredTickets() {
        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

<<<<<<< HEAD
    public List<Ticket> getAllTicketsAsList() {
        List<Ticket> list;
        synchronized (queue) {
            list = new ArrayList<>(queue);
        }

        int totalInMem = list.size();
        if (isAdmin) {
            LOG.info(">>> UI QUERY [ADMIN]: Showing all " + totalInMem + " tickets.");
        } else {
            list.removeIf(t -> !t.getOriginNodeId().equalsIgnoreCase(nodeId));
            LOG.info(">>> UI QUERY [REGULAR]: Showing " + list.size() + " of " + totalInMem + " tickets (owned by " + nodeId + ").");
        }

        list.sort(Comparator.comparingInt((Ticket t) -> {
            String status = t.getStatus();
            return (status != null && status.equals("WAITING")) ? 0 : 1;
        }).thenComparing(Ticket::compareTo));
=======
    /**
     * Returns all tickets VISIBLE to this node, sorted by status and FIFO.
     * All nodes now see all tickets.
     */
    public List<Ticket> getAllTicketsAsList() {
        List<Ticket> list = new ArrayList<>(queue);

        list.sort(Comparator.comparingInt((Ticket t) -> t.getStatus().equals("WAITING") ? 0 : 1)
                .thenComparing(Ticket::compareTo));
>>>>>>> origin/main
        return list;
    }

    public List<Ticket> getWaitingTickets() {
        return getAllTicketsAsList().stream()
                .filter(t -> "WAITING".equals(t.getStatus()))
                .toList();
    }

<<<<<<< HEAD
    public String getNodeId()                   { return nodeId; }
    public int getPeerCount()                   { return peers.size(); }

    private void notifyUIChanged() {
        if (onQueueChanged != null) {
            onQueueChanged.run();
        }
    }

    private Ticket findTicket(String ticketId) {
        synchronized (queue) {
            return queue.stream().filter(t -> t.getTicketId().equals(ticketId)).findFirst().orElse(null);
=======
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
>>>>>>> origin/main
        }
    }
}

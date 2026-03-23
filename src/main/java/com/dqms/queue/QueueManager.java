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

    public QueueManager(String nodeId, int tcpPort, DatabaseManager db, TCPClient client,
                        Map<String, NodeInfo> peers, boolean isAdmin) {
        this.nodeId  = nodeId;
        this.tcpPort = tcpPort;
        this.db      = db;
        this.client  = client;
        this.peers   = peers;
        this.isAdmin = isAdmin;
        LOG.info(">>> QUEUE MANAGER STARTUP: [" + nodeId + "] | ADMIN ROLE: " + isAdmin);
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
        loadFromDatabase(); 
        Message msg = Message.newTicket(nodeId, isAdmin, tcpPort, ticket);
        client.broadcast(peers.values(), msg);
        return ticket;
    }

    public synchronized void clearStudent(String ticketId) {
        if (!isAdmin) {
            LOG.warning(">>> REJECTED: Only Admin can clear tickets.");
            return;
        }
        LOG.info(">>> ADMIN ACTION: Clearing " + ticketId);
        db.updateStatus(ticketId, "CLEARED");
        loadFromDatabase(); 
        Message msg = Message.updateStatus(nodeId, isAdmin, tcpPort, ticketId, "CLEARED");
        client.broadcast(peers.values(), msg);
    }

    public synchronized void receiveTicket(Ticket ticket) {
        if (!db.ticketExists(ticket.getTicketId())) {
            LOG.info(">>> REPLICATING TICKET: " + ticket.getTicketId());
            db.insertTicket(ticket);
            loadFromDatabase();
        }
    }

    public synchronized void receiveStatusUpdate(String ticketId, String newStatus) {
        LOG.info(">>> REPLICATING STATUS: " + ticketId + " -> " + newStatus);
        // If the ticket doesn't exist locally, we still want to request a sync to ensure DB consistency
        if (!db.ticketExists(ticketId)) {
            LOG.warning(">>> MISSING TICKET DURING STATUS UPDATE. Requesting full sync...");
            // We should ideally sync here, but for now we just log.
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
        return list;
    }

    public List<Ticket> getWaitingTickets() {
        return getAllTicketsAsList().stream()
                .filter(t -> "WAITING".equals(t.getStatus()))
                .toList();
    }

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
        }
    }
}

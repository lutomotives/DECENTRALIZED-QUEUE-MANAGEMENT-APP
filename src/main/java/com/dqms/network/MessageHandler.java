package com.dqms.network;

import com.dqms.model.Message;
import com.dqms.model.Ticket;
import com.dqms.queue.QueueManager;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles a single inbound TCP connection from a peer node.
 *
 * <h3>Connection model</h3>
 * This class implements a <em>short-lived</em> connection pattern: one
 * connection → one message → close. This is consistent with how
 * {@link TCPClient#send} works and keeps state management simple.
 * The only exception is {@code SYNC_REQUEST}, which sends a response
 * back on the same socket before closing (request-response pair).
 *
 * <h3>Auto peer registration</h3>
 * Every inbound connection carries sender metadata (nodeId, tcpPort, role)
 * in the {@link Message} envelope. We use this to register the sender as
 * a known peer even if UDP discovery missed it. This provides a fallback
 * peer discovery path via TCP and keeps the peer map accurate.
 *
 * <h3>SYNC_RESPONSE routing note</h3>
 * A {@code SYNC_RESPONSE} arriving here would mean a peer spontaneously
 * pushed its full ticket list without a prior request — a push-based sync
 * initiated by the remote side. This is <em>not</em> the normal request-
 * response path (which is handled entirely in {@link TCPClient#requestSync}).
 * We handle it here to support future push-sync scenarios (e.g., a node
 * proactively sharing state with a newly discovered peer) without requiring
 * protocol changes.
 *
 * <h3>Socket timeout</h3>
 * {@link TCPServer} sets {@code SO_TIMEOUT = 5000ms} on each accepted socket
 * before handing it to this handler. If a peer connects but never sends data
 * (e.g., half-open connection), we time out and release the thread rather
 * than blocking indefinitely.
 */
public class MessageHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(MessageHandler.class.getName());

    private final Socket       socket;
    private final QueueManager queueManager;

    // ── Constructor ───────────────────────────────────────────────────────────

    public MessageHandler(Socket socket, QueueManager queueManager) {
        this.socket       = socket;
        this.queueManager = queueManager;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    @Override
    public void run() {
        String remoteAddr = socket.getInetAddress().getHostAddress();
        LOG.fine("Accepted connection from " + remoteAddr);

        // Try-with-resources closes the socket when the block exits, which
        // also closes its streams — no need to close OIS/OOS separately.
        try (socket;
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            Object received = in.readObject();
            if (!(received instanceof Message msg)) {
                LOG.warning("Non-Message object received from " + remoteAddr + " — ignored.");
                return;
            }

            // ── Auto-register the sender as a peer ────────────────────────
            // Even if UDP discovery hasn't seen this node yet, receiving a TCP
            // message from it is proof it exists. Register it now so we can
            // send messages back to it later.
            String senderIp = normalizeIp(socket.getInetAddress().getHostAddress());
            queueManager.registerPeer(
                    msg.getSenderNodeId(),
                    senderIp,
                    msg.getSenderTcpPort(),
                    msg.isSenderAdmin()
            );

            LOG.info(">>> [TCP] " + msg.getType()
                    + " from " + msg.getSenderNodeId() + " @ " + senderIp);

            route(msg, in);

        } catch (java.net.SocketTimeoutException e) {
            LOG.warning("Read timeout from " + remoteAddr
                    + " — peer connected but sent no data.");
        } catch (Exception e) {
            LOG.warning("MessageHandler error [" + remoteAddr + "]: " + e.getMessage());
        }
    }

    // ── Message router ────────────────────────────────────────────────────────

    /**
     * Routes an inbound message to the appropriate {@link QueueManager} method.
     *
     * @param msg the received message (never null, type already logged)
     * @param in  the open OIS — only used when we need to send a response
     *            on the same connection ({@code SYNC_REQUEST})
     */
    private void route(Message msg, ObjectInputStream in) {
        switch (msg.getType()) {

            case NEW_TICKET -> handleNewTicket(msg);

            case UPDATE_STATUS -> handleUpdateStatus(msg);

            case SYNC_REQUEST -> handleSyncRequest(msg);

            case SYNC_RESPONSE -> handleSyncResponse(msg);

            default -> LOG.warning("Unknown message type: " + msg.getType()
                    + " from " + msg.getSenderNodeId());
        }
    }

    // ── Individual handlers ───────────────────────────────────────────────────

    /**
     * A peer has registered a new ticket — add it to our local queue.
     * {@code receiveTicket} must be idempotent (ignore duplicates) because
     * the same ticket may arrive from multiple peers during a broadcast.
     */
    private void handleNewTicket(Message msg) {
        Ticket ticket = msg.getTicket();
        if (ticket == null) {
            LOG.warning("NEW_TICKET from " + msg.getSenderNodeId() + " carried null ticket.");
            return;
        }
        LOG.fine("NEW_TICKET: " + ticket.getTicketId());
        queueManager.receiveTicket(ticket);
    }

    /**
     * A peer has updated the status of an existing ticket.
     */
    private void handleUpdateStatus(Message msg) {
        String ticketId  = msg.getTicketId();
        String newStatus = msg.getNewStatus();
        if (ticketId == null || newStatus == null) {
            LOG.warning("UPDATE_STATUS from " + msg.getSenderNodeId()
                    + " missing ticketId or newStatus.");
            return;
        }
        LOG.fine("UPDATE_STATUS: ticketId=" + ticketId + " → " + newStatus);
        queueManager.receiveStatusUpdate(ticketId, newStatus);
    }

    /**
     * A peer wants our full ticket list so it can rebuild its queue.
     *
     * <p>We open an {@link ObjectOutputStream} on the existing socket to send
     * the response. The OOS must be flushed before being used to ensure its
     * 4-byte stream header reaches the peer before the payload — if the peer
     * has already constructed its {@link ObjectInputStream} and is waiting,
     * it needs that header to unblock.
     *
     * <p><b>Why we open OOS only here:</b> The {@code ObjectInputStream} in
     * {@link #run()} was constructed first (fine because the remote side,
     * {@link TCPClient}, flushed its OOS before we got here). If we had
     * constructed OOS at the start of the handler, and the remote side also
     * tried to construct OIS at the same time, we'd deadlock. Opening OOS
     * only when we actually need to reply avoids that scenario entirely.
     */
    private void handleSyncRequest(Message msg) {
        List<Ticket> all = queueManager.getAllTicketsAsList();
        LOG.info("SYNC_REQUEST from " + msg.getSenderNodeId()
                + " — sending " + all.size() + " ticket(s).");

        Message response = Message.syncResponse(
                queueManager.getNodeId(),
                queueManager.getTcpPort(),
                queueManager.isAdmin(),
                all
        );

        // OOS is constructed inside try-with-resources; socket stays open
        // (not closed) until the enclosing try-with-resources in run() exits.
        try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
            out.flush();          // send stream header first (unblocks peer's OIS ctor)
            out.writeObject(response);
            out.flush();
            LOG.fine("SYNC_RESPONSE sent to " + msg.getSenderNodeId());
        } catch (Exception e) {
            LOG.warning("Failed to send SYNC_RESPONSE to "
                    + msg.getSenderNodeId() + ": " + e.getMessage());
        }
    }

    /**
     * A peer has pushed its full ticket list to us without a prior request.
     *
     * <p>This is the push-based sync path. While the typical flow is
     * request-response (handled end-to-end in {@link TCPClient#requestSync}),
     * a peer may proactively push its state — for example, when it detects
     * via UDP that a new node has joined and wants to fast-track its
     * synchronization. We handle it here to remain open to that extension.
     */
    private void handleSyncResponse(Message msg) {
        List<Ticket> tickets = msg.getTicketList();
        if (tickets == null) {
            LOG.warning("SYNC_RESPONSE from " + msg.getSenderNodeId()
                    + " carried null ticket list.");
            return;
        }
        LOG.info("SYNC_RESPONSE (push) from " + msg.getSenderNodeId()
                + " — " + tickets.size() + " ticket(s).");
        queueManager.applySyncResponse(tickets);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Normalizes IPv6 loopback to canonical IPv4 loopback so that same-machine
     * peer connections resolve correctly when the JVM prefers IPv6.
     */
    private String normalizeIp(String ip) {
        return "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) ? "127.0.0.1" : ip;
    }
}
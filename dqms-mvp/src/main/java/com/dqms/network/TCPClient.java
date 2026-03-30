package com.dqms.network;

import com.dqms.model.Message;
import com.dqms.model.NodeInfo;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Sends messages to peer nodes over short-lived TCP connections.
 *
 * <h3>One connection per message</h3>
 * Each call to {@link #send} opens a fresh socket, writes one object, and
 * closes the connection. This is intentionally simple and stateless: no
 * connection pooling, no keep-alive. For the expected LAN scale (tens of
 * nodes, low-frequency ticket events) the overhead is negligible, and it
 * avoids the complexity of managing persistent connections and their
 * reconnect logic.
 *
 * <h3>OOS/OIS stream-header ordering</h3>
 * Java's {@link ObjectOutputStream} writes a 4-byte stream header on
 * construction. {@link ObjectInputStream} blocks in its constructor until it
 * reads that header. If both ends construct an OIS before the other has
 * flushed its OOS header, the connection deadlocks. The rule is:
 * <ol>
 *   <li>Always construct and flush OOS <em>before</em> constructing OIS.
 *   <li>On the sender side (this class), we never construct OIS — no risk.
 *   <li>On the requester side ({@link #requestSync}), we flush OOS before
 *       constructing OIS.
 *   <li>On the handler side ({@link MessageHandler}), OIS is constructed
 *       first (fine, because the sender has already flushed its OOS header
 *       before we reach that point).
 * </ol>
 *
 * <h3>Socket close vs. Thread.sleep(50)</h3>
 * The original code had a {@code Thread.sleep(50)} after flushing the OOS.
 * That was a fragile workaround: when the socket is closed, a TCP RST can
 * race the data and arrive at the peer before the last bytes are delivered.
 * The correct mechanism is {@code socket.setSoLinger(true, N)}, which makes
 * {@code socket.close()} block until all buffered data has been acknowledged
 * by the peer or N seconds elapse. We use a 2-second linger timeout.
 */
public class TCPClient {

    private static final Logger LOG = Logger.getLogger(TCPClient.class.getName());

    /** Timeout for establishing a connection to a peer. */
    private static final int CONNECT_TIMEOUT_MS = 3_000;

    /** Timeout for reading a response (used in {@link #requestSync}). */
    private static final int READ_TIMEOUT_MS    = 5_000;

    /**
     * How long {@code close()} will wait for the peer to acknowledge our data.
     * Replaces the original {@code Thread.sleep(50)} with a deterministic,
     * OS-managed guarantee. Value in seconds (minimum needed in practice: <1 s
     * on a LAN, 2 s gives headroom for a loaded receiver).
     */
    private static final int LINGER_SECONDS = 2;

    /** Delay between send attempts when retrying. */
    private static final int RETRY_DELAY_MS = 500;

    /** Number of send attempts before giving up. */
    private static final int MAX_SEND_ATTEMPTS = 2;

    private int myTcpPort; // set by QueueManager before any send

    /**
     * Cached thread pool for fire-and-forget broadcast sends. Tasks are
     * submitted asynchronously so a slow peer doesn't block the caller.
     * Shutdown is handled in {@link #shutdown()}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tcp-sender");
        t.setDaemon(true);
        return t;
    });

    // ── Configuration ─────────────────────────────────────────────────────────

    public void setMyTcpPort(int port) {
        this.myTcpPort = port;
    }

    // ── Core send ─────────────────────────────────────────────────────────────

    /**
     * Sends a message to a single peer with up to {@value #MAX_SEND_ATTEMPTS}
     * attempts.
     *
     * @return {@code true} if the message was delivered successfully
     */
    public boolean send(NodeInfo peer, Message message) {
        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            if (trySend(peer, message)) return true;
            if (attempt < MAX_SEND_ATTEMPTS) {
                LOG.fine("Retrying send to " + peer.getNodeId()
                        + " (attempt " + (attempt + 1) + "/" + MAX_SEND_ATTEMPTS + ")");
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        LOG.warning("Failed to deliver " + message.getType()
                + " to " + peer.getNodeId() + " after " + MAX_SEND_ATTEMPTS + " attempts.");
        return false;
    }

    /**
     * Makes a single send attempt. Returns {@code true} on success.
     */
    private boolean trySend(NodeInfo peer, Message message) {
        LOG.info("<<< [TCP] Sending " + message.getType()
                + " → " + peer.getNodeId() + " @ " + peer.getIpAddress() + ":" + peer.getTcpPort());
        try (Socket socket = openSocket(peer.getIpAddress(), peer.getTcpPort())) {
            // ObjectOutputStream flushes its 4-byte stream header immediately on
            // construction. The peer's MessageHandler will block in its OIS
            // constructor until it receives this header — flushing here
            // prevents that deadlock.
            try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
                out.flush();              // deliver stream header first
                out.writeObject(message); // then deliver the payload
                out.flush();
                // setSoLinger ensures close() blocks until the OS confirms the
                // peer has received all bytes — no sleep() needed.
            }
            return true;
        } catch (Exception e) {
            LOG.fine("Send attempt failed for " + peer.getNodeId() + ": " + e.getMessage());
            return false;
        }
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    /**
     * Broadcasts a message to all known peers asynchronously.
     *
     * <p>Each peer gets its own executor task so a slow or unreachable peer
     * does not delay delivery to healthy peers.
     */
    public void broadcast(Collection<NodeInfo> peers, Message message) {
        if (peers == null || peers.isEmpty()) {
            LOG.info("Broadcast skipped — no peers known yet.");
            return;
        }
        LOG.info("Broadcasting " + message.getType() + " to " + peers.size() + " peer(s).");
        for (NodeInfo peer : peers) {
            executor.submit(() -> send(peer, message));
        }
    }

    // ── Sync (request-response) ───────────────────────────────────────────────

    /**
     * Sends a {@code SYNC_REQUEST} to a peer and waits for a
     * {@code SYNC_RESPONSE} on the same connection.
     *
     * <p>This is the only true request-response exchange in the protocol;
     * all other messages are fire-and-forget. We keep the socket open after
     * writing the request so we can read the response directly — no second
     * connection needed.
     *
     * <p><b>OOS/OIS ordering:</b>
     * <ol>
     *   <li>We flush OOS (sends the stream header to the peer).
     *   <li>We write the SYNC_REQUEST object.
     *   <li>The peer's {@link MessageHandler} constructs its OIS (reads our
     *       header), processes the request, constructs its own OOS, flushes
     *       it, and writes the SYNC_RESPONSE.
     *   <li>We construct OIS here (reads the peer's OOS header) and read the
     *       response.
     * </ol>
     * Step 3 and 4 ordering is why we must not construct OIS here until
     * <em>after</em> the peer has had a chance to flush its OOS header.
     * Writing the request first (step 2) is the trigger that makes the peer
     * do step 3, so by the time we get to step 4 the peer's header is in
     * flight.
     *
     * @return the {@link Message} of type {@code SYNC_RESPONSE}, or
     *         {@code null} if the sync failed
     */
    public Message requestSync(NodeInfo peer, String myNodeId, boolean isAdmin, int myTcpPort) {
        LOG.info("<<< [TCP] Requesting SYNC from " + peer.getNodeId());
        try (Socket socket = openSocket(peer.getIpAddress(), peer.getTcpPort())) {
            socket.setSoTimeout(READ_TIMEOUT_MS);

            // Step 1 & 2: open OOS, flush header, write request.
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            out.writeObject(Message.syncRequest(myNodeId, myTcpPort, isAdmin));
            out.flush();

            // Step 4: now safe to construct OIS — peer's OOS header is in
            // flight (triggered by us writing the request in step 2).
            try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
                Object response = in.readObject();
                if (response instanceof Message msg) {
                    LOG.info("Received SYNC_RESPONSE from " + peer.getNodeId()
                            + " — " + msg.getTicketList().size() + " ticket(s).");
                    return msg;
                }
                LOG.warning("Unexpected response type from " + peer.getNodeId());
                return null;
            }
        } catch (Exception e) {
            LOG.warning("Sync request to " + peer.getNodeId() + " failed: " + e.getMessage());
            return null;
        }
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    /**
     * Shuts down the broadcast executor. Should be called when the application
     * exits. In-flight sends are given 3 seconds to complete.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Opens a socket with connection timeout and SO_LINGER configured.
     */
    private Socket openSocket(String ip, int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
        // setSoLinger(true, N): close() will block up to N seconds waiting for
        // the OS to confirm the peer received all buffered bytes. This replaces
        // the fragile Thread.sleep(50) used in the original implementation.
        socket.setSoLinger(true, LINGER_SECONDS);
        return socket;
    }
}
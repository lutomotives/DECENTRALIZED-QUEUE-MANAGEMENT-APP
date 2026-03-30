package com.dqms.network;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.dqms.queue.QueueManager;

/**
 * Runs a TCP server that accepts connections from peer nodes.
 *
 * <p>Each accepted connection is dispatched to a {@link MessageHandler}
 * running on a thread from a bounded pool. Bounding the pool prevents
 * resource exhaustion during bursts (e.g., many nodes reconnecting
 * simultaneously after a network partition).
 *
 * <h3>Clean shutdown</h3>
 * The original implementation stored no reference to the {@link ServerSocket},
 * so calling {@code stop()} only set a flag — the thread remained blocked
 * on {@code accept()} until the next connection arrived. The fix is to store
 * the socket as a field and close it in {@link #stop()}, which causes
 * {@code accept()} to throw a {@link SocketException} immediately, allowing
 * the loop to exit.
 *
 * <h3>Thread pool sizing</h3>
 * {@value #MAX_HANDLER_THREADS} concurrent handler threads is sufficient for
 * a campus LAN with dozens of nodes. Each handler is short-lived (one message
 * per connection), so slots recycle quickly. Adjust if the deployment grows.
 */
public class TCPServer implements Runnable {

    private static final Logger LOG = Logger.getLogger(TCPServer.class.getName());

    /** Maximum number of concurrent inbound connection handlers. */
    private static final int MAX_HANDLER_THREADS = 64;

    /**
     * Milliseconds to wait for in-flight handlers to finish after stop() is
     * called before forcing a shutdown.
     */
    private static final int SHUTDOWN_GRACE_MS = 3_000;

    private final int          port;
    private final QueueManager queueManager;

    /**
     * Stored as a volatile field so {@link #stop()} can close it from any
     * thread, unblocking the {@code accept()} call in {@link #run()}.
     */
    private volatile ServerSocket serverSocket;
    private volatile boolean      running = true;

    /**
     * Bounded thread pool. Using a fixed pool rather than
     * {@code newCachedThreadPool()} prevents uncapped thread creation
     * under load. Tasks beyond the limit queue until a slot is free;
     * connections are never silently dropped.
     */
    private final ExecutorService handlerPool =
            Executors.newFixedThreadPool(MAX_HANDLER_THREADS, r -> {
                Thread t = new Thread(r, "msg-handler");
                t.setDaemon(true);   // handlers must not prevent JVM exit
                return t;
            });

    // ── Constructor ───────────────────────────────────────────────────────────

    public TCPServer(int port, QueueManager queueManager) {
        this.port         = port;
        this.queueManager = queueManager;
    }

    // ── Main accept loop ──────────────────────────────────────────────────────

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            // SO_REUSEADDR lets us restart quickly without waiting for TIME_WAIT
            // on the port. Must be set before bind — ServerSocket(port) already
            // binds, so we set it afterwards for future restarts.
            serverSocket.setReuseAddress(true);
            LOG.info("TCPServer listening on port " + port);
        } catch (Exception e) {
            LOG.severe("TCPServer failed to bind on port " + port + ": " + e.getMessage());
            return;
        }

        while (running) {
            try {
                Socket client = serverSocket.accept();

                // Configure a read timeout on the accepted socket.
                // MessageHandler will block on ObjectInputStream.readObject();
                // without a timeout, a misbehaving or stalled peer can hold a
                // handler thread indefinitely.
                client.setSoTimeout(5_000);

                handlerPool.submit(new MessageHandler(client, queueManager));

            } catch (SocketException e) {
                // stop() closes serverSocket to break out of accept() — this
                // catch is the normal shutdown path, not an error.
                if (!running) break;
                LOG.warning("TCPServer socket error: " + e.getMessage());
            } catch (Exception e) {
                if (running) LOG.severe("TCPServer accept error: " + e.getMessage());
            }
        }

        shutdownPool();
        LOG.info("TCPServer stopped.");
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    /**
     * Signals the server to stop and immediately closes the {@link ServerSocket}.
     *
     * <p>Closing the socket is the only reliable way to unblock
     * {@code ServerSocket.accept()}, which otherwise waits indefinitely.
     * After the socket closes, the run() loop catches the resulting
     * {@link SocketException} and exits cleanly.
     */
    public void stop() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null && !ss.isClosed()) {
            try {
                ss.close();
            } catch (Exception e) {
                LOG.warning("Error closing TCPServer socket: " + e.getMessage());
            }
        }
    }

    /**
     * Attempts a graceful shutdown of the handler thread pool.
     * In-flight message handlers are given {@value #SHUTDOWN_GRACE_MS}ms to
     * complete before being interrupted.
     */
    private void shutdownPool() {
        handlerPool.shutdown();
        try {
            if (!handlerPool.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                LOG.warning("Handler pool did not finish within grace period — forcing shutdown.");
                handlerPool.shutdownNow();
            }
        } catch (InterruptedException ie) {
            handlerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int getPort() { return port; }
}
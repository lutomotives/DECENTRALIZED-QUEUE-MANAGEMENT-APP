package com.dqms.network;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.dqms.queue.QueueManager;

/**
 * Listens for incoming TCP connections from peer nodes.
 */
public class TCPServer implements Runnable {

    private static final Logger LOG = Logger.getLogger(TCPServer.class.getName());

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ServerSocket serverSocket;
    private final QueueManager queueManager;
    private volatile boolean running = true;

    public TCPServer(int port, QueueManager queueManager) throws IOException {
        this.queueManager = queueManager;
        this.serverSocket = new ServerSocket(port);
        LOG.info("TCPServer listening on port " + port);
    }

    @Override
    public void run() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                LOG.info("Accepted connection from " + clientSocket.getRemoteSocketAddress());
                // Handle the connection, perhaps with MessageHandler
                // For now, just close it
                clientSocket.close();
            } catch (IOException e) {
                if (running) {
                    LOG.severe("Server error: " + e.getMessage());
                }
            }
        }
    }

    public void shutdown() {
        try {
            serverSocket.close();
            executor.shutdown();
        } catch (IOException e) {
            LOG.severe("Error shutting down server: " + e.getMessage());
        }
    }
}
package com.dqms.network;

import com.dqms.model.Message;
import com.dqms.queue.QueueManager;

import java.io.ObjectInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Logger;

/**
 * Runs a TCP ServerSocket in a background daemon thread.
 * Each accepted connection is handled in its own thread by MessageHandler.
 */
public class TCPServer implements Runnable {

    private static final Logger LOG = Logger.getLogger(TCPServer.class.getName());

    private final int port;
    private final QueueManager queueManager;
    private volatile boolean running = true;

    public TCPServer(int port, QueueManager queueManager) {
        this.port = port;
        this.queueManager = queueManager;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            LOG.info("TCPServer listening on port " + port);
            while (running) {
                Socket client = serverSocket.accept();
                // Each connection is handled in its own thread
                Thread handler = new Thread(new MessageHandler(client, queueManager), "msg-handler");
                handler.setDaemon(true);
                handler.start();
            }
        } catch (Exception e) {
            if (running) LOG.severe("TCPServer error: " + e.getMessage());
        }
    }

    public void stop() { running = false; }
}

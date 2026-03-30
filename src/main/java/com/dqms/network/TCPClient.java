package com.dqms.network;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.dqms.model.Message;
import com.dqms.model.NodeInfo;

/**
 * Sends messages to peer nodes over TCP.
 */
public class TCPClient {

    private static final Logger LOG = Logger.getLogger(TCPClient.class.getName());
    private static final int TIMEOUT_MS = 3000;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    
    private int myTcpPort;

    public void setMyTcpPort(int port) {
        this.myTcpPort = port;
    }

    public boolean send(NodeInfo peer, Message message) {
        LOG.info("<<< [TCP] Sending " + message.getType() + " to " + peer.getNodeId() + " (" + peer.getIpAddress() + ")");
        try (Socket socket = new Socket(peer.getIpAddress(), peer.getTcpPort())) {
            socket.setSoTimeout(TIMEOUT_MS);
            
            // Header is written immediately upon construction
            try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
                out.writeObject(message);
                out.flush();
                // Brief pause to allow the receiving node to process the ObjectInputStream 
                // before the socket is forcefully closed and torn down.
                Thread.sleep(50);
            }
            return true;
        } catch (Exception e) {
            LOG.warning("Failed to send " + message.getType() + " to " + peer.getNodeId() + ": " + e.getMessage());
            return false;
        }
    }

    public void broadcast(Collection<NodeInfo> peers, Message message) {
        if (peers == null || peers.isEmpty()) {
            LOG.info("Broadcast skipped: No peers discovered yet.");
            return;
        }
        LOG.info("Broadcasting " + message.getType() + " to " + peers.size() + " peers.");
        for (NodeInfo peer : peers) {
            executor.submit(() -> send(peer, message));
        }
    }

    public Message requestSync(NodeInfo peer, String myNodeId, boolean isAdmin) {
        LOG.info("<<< [TCP] Requesting SYNC from " + peer.getNodeId());
        try (Socket socket = new Socket(peer.getIpAddress(), peer.getTcpPort())) {
            socket.setSoTimeout(5000);
            
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush(); // Send header
            
            // WRITE THE REQUEST FIRST
            out.writeObject(Message.syncRequest(myNodeId, myTcpPort, isAdmin));
            out.flush();

            // THEN open InputStream to wait for the response header
            try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
                Object response = in.readObject();
                if (response instanceof Message) {
                    LOG.info("Received SYNC_RESPONSE from " + peer.getNodeId());
                    return (Message) response;
                }
            }
            return null;
        } catch (Exception e) {
            LOG.warning("Sync request to " + peer.getNodeId() + " failed: " + e.getMessage());
            return null;
        }
    }
}

package com.dqms.network;

import com.dqms.model.Message;
import com.dqms.model.NodeInfo;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Collection;
import java.util.logging.Logger;

/**
 * Sends messages to peer nodes over TCP.
 * Each send opens a new connection, writes the message, and closes.
 */
public class TCPClient {

    private static final Logger LOG = Logger.getLogger(TCPClient.class.getName());
    private static final int TIMEOUT_MS = 3000;

    /**
     * Send a message to a single peer. Returns true if successful.
     */
    public boolean send(NodeInfo peer, Message message) {
        try (Socket socket = new Socket(peer.getIpAddress(), peer.getTcpPort())) {
            socket.setSoTimeout(TIMEOUT_MS);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.writeObject(message);
            out.flush();
            return true;
        } catch (Exception e) {
            LOG.warning("Failed to send to " + peer + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Broadcast a message to all known peers.
     */
    public void broadcast(Collection<NodeInfo> peers, Message message) {
        for (NodeInfo peer : peers) {
            send(peer, message);
        }
    }

    /**
     * Send a SYNC_REQUEST and return the SYNC_RESPONSE from the peer.
     * Returns null if the request fails.
     */
    public Message requestSync(NodeInfo peer, String myNodeId) {
        try (Socket socket = new Socket(peer.getIpAddress(), peer.getTcpPort())) {
            socket.setSoTimeout(5000);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream  in  = new ObjectInputStream(socket.getInputStream());

            out.writeObject(Message.syncRequest(myNodeId));
            out.flush();

            Message response = (Message) in.readObject();
            LOG.info("Received SYNC_RESPONSE from " + peer);
            return response;
        } catch (Exception e) {
            LOG.warning("Sync request to " + peer + " failed: " + e.getMessage());
            return null;
        }
    }
}

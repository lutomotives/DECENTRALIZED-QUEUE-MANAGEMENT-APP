package com.dqms.network;

import com.dqms.model.Message;
import com.dqms.model.Ticket;
import com.dqms.queue.QueueManager;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles a single incoming TCP connection from a peer.
 */
public class MessageHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(MessageHandler.class.getName());

    private final Socket socket;
    private final QueueManager queueManager;

    public MessageHandler(Socket socket, QueueManager queueManager) {
        this.socket = socket;
        this.queueManager = queueManager;
    }

    @Override
    public void run() {
        try (socket;
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            Object received = in.readObject();
            if (!(received instanceof Message)) return;
            
            Message msg = (Message) received;
            String senderId = msg.getSenderNodeId();
            
            // Auto-register peer
            String senderIp = socket.getInetAddress().getHostAddress();
            if (senderIp.equals("0:0:0:0:0:0:0:1")) senderIp = "127.0.0.1";
            queueManager.registerPeer(senderId, senderIp, msg.getSenderTcpPort(), msg.isSenderAdmin());

            LOG.info(">>> [TCP] Received " + msg.getType() + " from " + senderId);

            switch (msg.getType()) {
                case NEW_TICKET ->
                        queueManager.receiveTicket(msg.getTicket());

                case UPDATE_STATUS ->
                        queueManager.receiveStatusUpdate(msg.getTicketId(), msg.getNewStatus());

                case SYNC_REQUEST -> {
                    List<Ticket> all = queueManager.getUnfilteredTickets(); 
                    Message response = Message.syncResponse(
                            queueManager.getNodeId(),
                            queueManager.isAdmin(),
                            0, 
                            all
                    );
                    // Only open OutputStream when replying
                    try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
                        out.writeObject(response);
                        out.flush();
                    }
                }

                case SYNC_RESPONSE ->
                        queueManager.applySyncResponse(msg.getTicketList());
            }
        } catch (Exception e) {
            LOG.warning("MessageHandler error: " + e.getMessage());
        }
    }
}

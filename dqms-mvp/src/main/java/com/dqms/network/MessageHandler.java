package com.dqms.network;

import com.dqms.model.Message;
import com.dqms.queue.QueueManager;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.logging.Logger;

/**
 * Handles a single incoming TCP connection from a peer.
 * Reads one Message, routes it to QueueManager, then closes the connection.
 *
 * Routing:
 *   NEW_TICKET    → queueManager.receiveTicket(ticket)
 *   UPDATE_STATUS → queueManager.receiveStatusUpdate(ticketId, status)
 *   SYNC_REQUEST  → reply with SYNC_RESPONSE carrying all current tickets
 *   SYNC_RESPONSE → queueManager.applySyncResponse(ticketList)
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
             ObjectInputStream in  = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            Message msg = (Message) in.readObject();
            LOG.info("Received " + msg.getType() + " from " + msg.getSenderNodeId());

            switch (msg.getType()) {

                case NEW_TICKET ->
                        queueManager.receiveTicket(msg.getTicket());

                case UPDATE_STATUS ->
                        queueManager.receiveStatusUpdate(msg.getTicketId(), msg.getNewStatus());

                case SYNC_REQUEST -> {
                    // Respond with our full ticket list
                    Message response = Message.syncResponse(
                            queueManager.getNodeId(),
                            queueManager.getAllTicketsAsList()
                    );
                    out.writeObject(response);
                    out.flush();
                    LOG.info("Sent SYNC_RESPONSE with " + queueManager.getAllTicketsAsList().size() + " tickets");
                }

                case SYNC_RESPONSE ->
                        queueManager.applySyncResponse(msg.getTicketList());
            }

        } catch (Exception e) {
            LOG.warning("MessageHandler error: " + e.getMessage());
        }
    }
}

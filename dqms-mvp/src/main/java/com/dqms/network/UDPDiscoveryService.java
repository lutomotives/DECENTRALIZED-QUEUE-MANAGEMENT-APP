package com.dqms.network;

import com.dqms.model.NodeInfo;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Handles UDP-based peer discovery.
 */
public class UDPDiscoveryService implements Runnable {

    private static final Logger LOG = Logger.getLogger(UDPDiscoveryService.class.getName());
    private static final int DISCOVERY_PORT = 5000;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final DatagramSocket socket;
    private final int tcpPort;
    private final String nodeId;
    private final Map<String, NodeInfo> peers;
    private final Consumer<NodeInfo> onPeerDiscovered;
    private volatile boolean running = true;

    public UDPDiscoveryService(String nodeId, int tcpPort, boolean isAdmin, Map<String, NodeInfo> peers, Consumer<NodeInfo> onPeerDiscovered) throws IOException {
        this.nodeId = nodeId;
        this.tcpPort = tcpPort;
        this.peers = peers;
        this.onPeerDiscovered = onPeerDiscovered;
        this.socket = new DatagramSocket(DISCOVERY_PORT);
        LOG.log(java.util.logging.Level.INFO, "UDPDiscoveryService started on port {0}", DISCOVERY_PORT);
    }

    @Override
    public void run() {
        // Send announcement
        executor.submit(() -> {
            try {
                String message = "DQMS:" + nodeId + ":" + tcpPort;
                byte[] data = message.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
                socket.send(packet);
                LOG.log(java.util.logging.Level.INFO, "Sent discovery announcement: {0}", message);
            } catch (IOException e) {
                LOG.log(java.util.logging.Level.SEVERE, "Failed to send discovery: {0}", e.getMessage());
            }
        });

        // Listen for responses
        executor.submit(() -> {
            byte[] buffer = new byte[1024];
            while (running && !socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String received = new String(packet.getData(), 0, packet.getLength());
                    if (received.startsWith("DQMS:")) {
                        String[] parts = received.split(":");
                        if (parts.length >= 3) {
                            String peerNodeId = parts[1];
                            int peerTcpPort = Integer.parseInt(parts[2]);
                            NodeInfo peer = new NodeInfo(peerNodeId, packet.getAddress().getHostAddress(), peerTcpPort);
                            if (!peers.containsKey(peerNodeId)) {
                                peers.put(peerNodeId, peer);
                                onPeerDiscovered.accept(peer);
                                LOG.log(java.util.logging.Level.INFO, "Discovered new peer: {0}", peer);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        LOG.log(java.util.logging.Level.SEVERE, "Discovery receive error: {0}", e.getMessage());
                    }
                }
            }
        });
    }

    public void shutdown() {
        running = false;
        socket.close();
        executor.shutdown();
    }
}
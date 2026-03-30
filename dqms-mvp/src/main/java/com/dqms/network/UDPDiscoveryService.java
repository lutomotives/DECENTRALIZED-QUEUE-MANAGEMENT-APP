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
public class UDPDiscoveryService {

    private static final Logger LOG = Logger.getLogger(UDPDiscoveryService.class.getName());
    private static final int DISCOVERY_PORT = 5000;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final DatagramSocket socket;
    private final int tcpPort;
    private final String nodeId;
    private final boolean isAdmin;
    private final Map<String, NodeInfo> peers;
    private final Consumer<NodeInfo> onPeerDiscovered;

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
    private final boolean isAdmin;
    private final Map<String, NodeInfo> peers;
    private final Consumer<NodeInfo> onPeerDiscovered;
    private volatile boolean running = true;

    public UDPDiscoveryService(String nodeId, int tcpPort, boolean isAdmin, Map<String, NodeInfo> peers, Consumer<NodeInfo> onPeerDiscovered) throws IOException {
        this.nodeId = nodeId;
        this.tcpPort = tcpPort;
        this.isAdmin = isAdmin;
        this.peers = peers;
        this.onPeerDiscovered = onPeerDiscovered;
        this.socket = new DatagramSocket(DISCOVERY_PORT);
        LOG.info("UDPDiscoveryService started on port " + DISCOVERY_PORT);
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
                LOG.info("Sent discovery announcement: " + message);
            } catch (IOException e) {
                LOG.severe("Failed to send discovery: " + e.getMessage());
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
                                LOG.info("Discovered new peer: " + peer);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        LOG.severe("Discovery receive error: " + e.getMessage());
                    }
                }
            }
        });
    }
        // Send announcement
        executor.submit(() -> {
            try {
                String message = "DQMS:" + nodeId + ":" + tcpPort;
                byte[] data = message.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
                socket.send(packet);
                LOG.info("Sent discovery announcement: " + message);
            } catch (IOException e) {
                LOG.severe("Failed to send discovery: " + e.getMessage());
            }
        });

        // Listen for responses
        executor.submit(() -> {
            byte[] buffer = new byte[1024];
            while (!socket.isClosed()) {
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
                                LOG.info("Discovered new peer: " + peer);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        LOG.severe("Discovery receive error: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void shutdown() {
        socket.close();
        executor.shutdown();
    }
}
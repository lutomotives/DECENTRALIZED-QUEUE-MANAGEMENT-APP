package com.dqms.network;

import com.dqms.model.NodeInfo;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Discovers peers on the network using UDP multicast.
 *
 * Protocol:
 *   Announcement packet format: "DQMS_NODE:<nodeId>:<tcpPort>"
 *
 * For single-machine testing (2 nodes on localhost), multicast still
 * works because the loopback interface supports multicast. Both nodes
 * will hear each other's announcements.
 *
 * Multicast group : 230.0.0.0
 * UDP port        : 4446
 */
public class UDPDiscoveryService implements Runnable {

    private static final Logger LOG = Logger.getLogger(UDPDiscoveryService.class.getName());

    public static final String MULTICAST_GROUP = "230.0.0.0";
    public static final int    UDP_PORT        = 4446;
    private static final String ANNOUNCE_PREFIX = "DQMS_NODE:";

    private final String nodeId;
    private final int    tcpPort;
    private final Map<String, NodeInfo> peers;       // nodeId → NodeInfo
    private final Consumer<NodeInfo>    onPeerFound; // callback for new peer
    private volatile boolean running = true;

    public UDPDiscoveryService(String nodeId, int tcpPort,
                                Map<String, NodeInfo> peers,
                                Consumer<NodeInfo> onPeerFound) {
        this.nodeId      = nodeId;
        this.tcpPort     = tcpPort;
        this.peers       = peers;
        this.onPeerFound = onPeerFound;
    }

    @Override
    public void run() {
        try (MulticastSocket socket = new MulticastSocket(UDP_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);

            // Enable loopback so nodes on the same machine hear each other
            socket.setLoopbackMode(false);
            socket.joinGroup(group);

            // Announce ourselves in a separate thread
            Thread announcer = new Thread(this::announceLoop, "udp-announcer");
            announcer.setDaemon(true);
            announcer.start();

            // Listen for peer announcements
            byte[] buf = new byte[256];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                handleAnnouncement(msg, packet.getAddress().getHostAddress());
            }

            socket.leaveGroup(group);
        } catch (Exception e) {
            if (running) LOG.severe("UDPDiscovery error: " + e.getMessage());
        }
    }

    private void announceLoop() {
        String announcement = ANNOUNCE_PREFIX + nodeId + ":" + tcpPort;
        byte[] data = announcement.getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            DatagramPacket packet = new DatagramPacket(data, data.length, group, UDP_PORT);

            while (running) {
                socket.send(packet);
                LOG.fine("Announced: " + announcement);
                Thread.sleep(2000); // announce every 2 seconds
            }
        } catch (Exception e) {
            if (running) LOG.warning("Announcer error: " + e.getMessage());
        }
    }

    private void handleAnnouncement(String msg, String senderIp) {
        if (!msg.startsWith(ANNOUNCE_PREFIX)) return;

        // Format: DQMS_NODE:<nodeId>:<tcpPort>
        String[] parts = msg.substring(ANNOUNCE_PREFIX.length()).split(":");
        if (parts.length != 2) return;

        String peerId   = parts[0];
        int    peerPort;
        try { peerPort = Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) { return; }

        // Ignore our own announcement
        if (peerId.equals(nodeId)) return;

        // Register new peer
        if (!peers.containsKey(peerId)) {
            NodeInfo peer = new NodeInfo(peerId, senderIp, peerPort);
            peers.put(peerId, peer);
            LOG.info("Discovered peer: " + peer);
            if (onPeerFound != null) onPeerFound.accept(peer);
        }
    }

    public void stop() { running = false; }
}

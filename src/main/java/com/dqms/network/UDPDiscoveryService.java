package com.dqms.network;

import com.dqms.model.NodeInfo;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Discovers peers on the network using UDP multicast.
 * Announcement: "DQMS_NODE:<nodeId>:<tcpPort>:<ROLE>"
 */
public class UDPDiscoveryService implements Runnable {

    private static final Logger LOG = Logger.getLogger(UDPDiscoveryService.class.getName());

    public static final String MULTICAST_GROUP = "239.0.0.1";
    public static final int    UDP_PORT        = 4446;
    private static final String ANNOUNCE_PREFIX = "DQMS_NODE:";

    private final String nodeId;
    private final int    tcpPort;
    private final boolean isAdmin;
    private final Map<String, NodeInfo> peers;
    private final Consumer<NodeInfo>    onPeerFound;
    private volatile boolean running = true;

    public UDPDiscoveryService(String nodeId, int tcpPort, boolean isAdmin,
                                Map<String, NodeInfo> peers,
                                Consumer<NodeInfo> onPeerFound) {
        this.nodeId      = nodeId;
        this.tcpPort     = tcpPort;
        this.isAdmin     = isAdmin;
        this.peers       = peers;
        this.onPeerFound = onPeerFound;
    }

    @Override
    public void run() {
        LOG.info("UDP Discovery active on " + MULTICAST_GROUP + ":" + UDP_PORT);
        try (MulticastSocket socket = new MulticastSocket(UDP_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            InetSocketAddress groupAddress = new InetSocketAddress(group, UDP_PORT);
            
            // Join group on all available interfaces to handle multi-NIC Windows machines
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp() && iface.supportsMulticast() && !iface.isVirtual()) {
                    try {
                        socket.joinGroup(groupAddress, iface);
                        LOG.fine("Joined multicast group on interface: " + iface.getDisplayName());
                    } catch (Exception e) {
                        // Some interfaces might fail to join, ignore them
                    }
                }
            }

            socket.setLoopbackMode(false); // ENABLE loopback

            Thread announcer = new Thread(this::announceLoop, "udp-announcer");
            announcer.setDaemon(true);
            announcer.start();

            byte[] buf = new byte[256];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                handleAnnouncement(msg, packet.getAddress().getHostAddress());
            }
            
            // Leave group on all interfaces
            interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                try { socket.leaveGroup(groupAddress, iface); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (running) LOG.severe("UDPDiscovery error: " + e.getMessage());
        }
    }

    private void announceLoop() {
        String announcement = ANNOUNCE_PREFIX + nodeId + ":" + tcpPort + ":" + (isAdmin ? "ADMIN" : "REGULAR");
        byte[] data = announcement.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            DatagramPacket packet = new DatagramPacket(data, data.length, group, UDP_PORT);
            while (running) {
                socket.send(packet);
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            if (running) LOG.warning("Announcer error: " + e.getMessage());
        }
    }

    private void handleAnnouncement(String msg, String senderIp) {
        if (!msg.startsWith(ANNOUNCE_PREFIX)) return;
        String[] parts = msg.substring(ANNOUNCE_PREFIX.length()).split(":");
        if (parts.length != 3) return;

        String peerId   = parts[0];
        int    peerPort;
        try { peerPort = Integer.parseInt(parts[1]); } catch (Exception e) { return; }
        boolean peerIsAdmin = "ADMIN".equalsIgnoreCase(parts[2]);

        if (peerId.equals(nodeId)) return;

        if (!peers.containsKey(peerId)) {
            // Use 127.0.0.1 if sender is from this machine to avoid routing issues
            String targetIp = senderIp;
            if (senderIp.equals("0:0:0:0:0:0:0:1")) targetIp = "127.0.0.1";

            NodeInfo peer = new NodeInfo(peerId, targetIp, peerPort, peerIsAdmin);
            peers.put(peerId, peer);
            LOG.info("Discovered peer: " + peer);
            if (onPeerFound != null) onPeerFound.accept(peer);
        }
    }

    public void stop() { running = false; }
}

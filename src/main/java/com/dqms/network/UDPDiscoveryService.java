package com.dqms.network;

import com.dqms.model.NodeInfo;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Discovers peers on the LAN using UDP multicast.
 *
 * <p><b>Protocol:</b> Every node broadcasts an announcement every
 * {@value #ANNOUNCE_INTERVAL_MS}ms:
 * <pre>DQMS_NODE:{nodeId}:{tcpPort}:{ADMIN|REGULAR}</pre>
 *
 * <p><b>Multicast group choice:</b> We use {@value #MULTICAST_GROUP} which
 * falls in the administratively-scoped range (239.x.x.x, RFC 2365). This
 * range is guaranteed to stay within a private network — routers must not
 * forward it. The spec document mentions 230.0.0.0 which is in the globally
 * routed range; that is a spec error. 239.0.0.1 is the correct choice here.
 *
 * <p><b>Peer expiry:</b> A peer is removed from the shared map if no
 * announcement is received from it within {@value #PEER_EXPIRY_MS}ms
 * (= {@value #ANNOUNCE_INTERVAL_MS}ms × 5 missed heartbeats).
 * This keeps the peer list accurate after a node crashes or is unplugged.
 */
public class UDPDiscoveryService implements Runnable {

    private static final Logger LOG = Logger.getLogger(UDPDiscoveryService.class.getName());

    // ── Protocol constants ────────────────────────────────────────────────────
    public  static final String MULTICAST_GROUP    = "239.0.0.1";
    public  static final int    UDP_PORT           = 4446;
    private static final String ANNOUNCE_PREFIX    = "DQMS_NODE:";

    // ── Timing ────────────────────────────────────────────────────────────────
    private static final int ANNOUNCE_INTERVAL_MS  = 2_000;
    private static final int PEER_EXPIRY_MS        = ANNOUNCE_INTERVAL_MS * 5; // 10 s

    // ── Multicast config ─────────────────────────────────────────────────────
    /**
     * TTL = 1 keeps packets within a single network segment (no router hops).
     * This is intentional: DQMS nodes are expected to be on the same LAN.
     */
    private static final int MULTICAST_TTL = 1;

    // ── Instance state ────────────────────────────────────────────────────────
    private final String              nodeId;
    private final int                 tcpPort;
    private final boolean             isAdmin;
    private final Map<String, NodeInfo> peers;
    private final Consumer<NodeInfo>    onPeerFound;
    private volatile boolean running = true;

    public UDPDiscoveryService(String nodeId, int tcpPort, boolean isAdmin,
                                Map<String, NodeInfo> peers,
                                Consumer<NodeInfo> onPeerFound) {
=======
    // ── Protocol constants ────────────────────────────────────────────────────
    public  static final String MULTICAST_GROUP    = "239.0.0.1";
    public  static final int    UDP_PORT           = 4446;
    private static final String ANNOUNCE_PREFIX    = "DQMS_NODE:";

    // ── Timing ────────────────────────────────────────────────────────────────
    private static final int ANNOUNCE_INTERVAL_MS  = 2_000;
    private static final int PEER_EXPIRY_MS        = ANNOUNCE_INTERVAL_MS * 5; // 10 s

    // ── Multicast config ─────────────────────────────────────────────────────
    /**
     * TTL = 1 keeps packets within a single network segment (no router hops).
     * This is intentional: DQMS nodes are expected to be on the same LAN.
     */
    private static final int MULTICAST_TTL = 1;

    // ── Instance state ────────────────────────────────────────────────────────
    private final String              nodeId;
    private final int                 tcpPort;
    private final boolean             isAdmin;
    private final Map<String, NodeInfo> peers;       // shared with QueueManager
    private final Consumer<NodeInfo>  onPeerFound;   // called when a NEW peer appears
    private final Consumer<NodeInfo>  onPeerLost;    // called when a peer expires

    /**
     * lastSeen tracks the timestamp (ms) of the most recent announcement from
     * each peer. The expiry thread uses this to evict stale entries.
     */
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();

    private volatile boolean running = true;

    /**
     * The live socket is stored as a field so {@link #stop()} can close it,
     * which unblocks the blocking {@code socket.receive()} call immediately.
     * Without this, the receive thread would hang until the next packet arrives
     * or the OS-level socket timeout fires.
     */
    private volatile MulticastSocket liveSocket;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param nodeId      this node's unique identifier
     * @param tcpPort     the port our TCPServer is listening on
     * @param isAdmin     whether this node is running in admin mode
     * @param peers       shared peer map (ConcurrentHashMap recommended)
     * @param onPeerFound callback fired when a previously-unknown peer is found
     * @param onPeerLost  callback fired when a peer's heartbeat expires;
     *                    pass {@code null} if you don't need this event
     */
    public UDPDiscoveryService(String nodeId, int tcpPort, boolean isAdmin,
                               Map<String, NodeInfo> peers,
                               Consumer<NodeInfo> onPeerFound,
                               Consumer<NodeInfo> onPeerLost) {
>>>>>>> acdbfea (	modified:   dqms-mvp/src/main/java/com/dqms/app/Main.java):dqms-mvp/src/main/java/com/dqms/network/UDPDiscoveryService.java
        this.nodeId      = nodeId;
        this.tcpPort     = tcpPort;
        this.isAdmin     = isAdmin;
        this.peers       = peers;
        this.onPeerFound = onPeerFound;
        this.onPeerLost  = onPeerLost;
    }

    // ── Main receive loop ─────────────────────────────────────────────────────

    @Override
    public void run() {
<<<<<<< HEAD:src/main/java/com/dqms/network/UDPDiscoveryService.java
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
=======
        LOG.info("UDP Discovery starting — group=" + MULTICAST_GROUP + "  port=" + UDP_PORT);

        try {
            liveSocket = buildAndJoinSocket();
        } catch (Exception e) {
            LOG.severe("Failed to create multicast socket: " + e.getMessage());
            return;
        }

        // Announcer runs in a separate daemon thread so this thread can block
        // on receive() without missing any announce windows.
        startDaemon("udp-announcer", this::announceLoop);

        // Expiry checker also runs as a daemon — no need to join on shutdown.
        startDaemon("udp-expiry",    this::expiryLoop);

        LOG.info("UDP Discovery active.");

        byte[] buf = new byte[512];
        try {
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    liveSocket.receive(packet);
                } catch (SocketException e) {
                    // stop() closes the socket to unblock this call — expected path.
                    if (!running) break;
                    LOG.warning("Socket error while receiving: " + e.getMessage());
                    continue;
                }
                String msg       = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                String senderIp  = normalizeIp(packet.getAddress().getHostAddress());
                handleAnnouncement(msg, senderIp);
            }
>>>>>>> acdbfea (	modified:   dqms-mvp/src/main/java/com/dqms/app/Main.java):dqms-mvp/src/main/java/com/dqms/network/UDPDiscoveryService.java
        } catch (Exception e) {
            if (running) LOG.severe("UDPDiscovery receive error: " + e.getMessage());
        } finally {
            safeLeaveAndClose(liveSocket);
            LOG.info("UDP Discovery stopped.");
        }
    }

<<<<<<< HEAD:src/main/java/com/dqms/network/UDPDiscoveryService.java
    private void announceLoop() {
        String announcement = ANNOUNCE_PREFIX + nodeId + ":" + tcpPort + ":" + (isAdmin ? "ADMIN" : "REGULAR");
        byte[] data = announcement.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            DatagramPacket packet = new DatagramPacket(data, data.length, group, UDP_PORT);
            while (running) {
                socket.send(packet);
                Thread.sleep(2000);
=======
    // ── Socket construction ───────────────────────────────────────────────────

    private MulticastSocket buildAndJoinSocket() throws Exception {
        MulticastSocket socket = new MulticastSocket(UDP_PORT);
        socket.setTimeToLive(MULTICAST_TTL);

        // setLoopbackMode(false) → ENABLES loopback delivery.
        // Java's naming is inverted relative to the POSIX IP_MULTICAST_LOOP
        // socket option: false = loopback ON, true = loopback OFF.
        // We enable it so that two nodes on the same machine discover each other
        // during development/testing without needing a second physical host.
        socket.setLoopbackMode(false);

        InetAddress       group        = InetAddress.getByName(MULTICAST_GROUP);
        InetSocketAddress groupAddress = new InetSocketAddress(group, UDP_PORT);

        // Join on every eligible interface so we receive announcements
        // regardless of which NIC the sender's packet arrives on.
        // This is important on Windows machines with Wi-Fi + Ethernet active.
        int joined = 0;
        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        while (ifaces != null && ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();
            if (isEligibleInterface(iface)) {
                try {
                    socket.joinGroup(groupAddress, iface);
                    LOG.fine("Joined multicast group on: " + iface.getDisplayName());
                    joined++;
                } catch (Exception e) {
                    LOG.fine("Could not join on " + iface.getDisplayName() + ": " + e.getMessage());
                }
            }
        }

        if (joined == 0) {
            LOG.warning("No interfaces joined multicast group — peer discovery may not function.");
        } else {
            LOG.info("Joined multicast group on " + joined + " interface(s).");
        }

        return socket;
    }

    private boolean isEligibleInterface(NetworkInterface iface) {
        try {
            return iface.isUp()
                && iface.supportsMulticast()
                && !iface.isVirtual()
                && !iface.isLoopback(); // loopback NIC handled by setLoopbackMode above
        } catch (SocketException e) {
            return false;
        }
    }

    // ── Announce loop ─────────────────────────────────────────────────────────

    /**
     * Sends a periodic announcement to the multicast group so that all peers
     * on the LAN know this node is alive. Also serves as the heartbeat that
     * the expiry loop monitors.
     */
    private void announceLoop() {
        String role         = isAdmin ? "ADMIN" : "REGULAR";
        String announcement = ANNOUNCE_PREFIX + nodeId + ":" + tcpPort + ":" + role;
        byte[] data         = announcement.getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket sender = new DatagramSocket()) {
            // Bind the sender to a specific interface if desired; for now let
            // the OS choose (typically the default-route interface).
            InetAddress group  = InetAddress.getByName(MULTICAST_GROUP);
            DatagramPacket pkt = new DatagramPacket(data, data.length, group, UDP_PORT);

            while (running) {
                try {
                    sender.send(pkt);
                } catch (Exception e) {
                    if (running) LOG.warning("Announce send failed: " + e.getMessage());
                }
                try {
                    Thread.sleep(ANNOUNCE_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
>>>>>>> acdbfea (	modified:   dqms-mvp/src/main/java/com/dqms/app/Main.java):dqms-mvp/src/main/java/com/dqms/network/UDPDiscoveryService.java
            }
        } catch (Exception e) {
            if (running) LOG.warning("Announcer socket error: " + e.getMessage());
        }
    }

    // ── Expiry loop ───────────────────────────────────────────────────────────

    /**
     * Periodically scans {@link #lastSeen} and evicts any peer whose
     * last announcement is older than {@value #PEER_EXPIRY_MS}ms.
     *
     * <p>Running at half the expiry interval gives us a worst-case detection
     * latency of 1.5× the expiry window — acceptable for a LAN heartbeat.
     */
    private void expiryLoop() {
        long checkInterval = PEER_EXPIRY_MS / 2L;
        while (running) {
            try {
                Thread.sleep(checkInterval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = System.currentTimeMillis();
            lastSeen.forEach((peerId, ts) -> {
                if (now - ts > PEER_EXPIRY_MS) {
                    lastSeen.remove(peerId);
                    NodeInfo lost = peers.remove(peerId);
                    if (lost != null) {
                        LOG.info("Peer expired (no heartbeat): " + peerId);
                        if (onPeerLost != null) onPeerLost.accept(lost);
                    }
                }
            });
        }
    }

    // ── Announcement handler ──────────────────────────────────────────────────

    private void handleAnnouncement(String msg, String senderIp) {
        if (!msg.startsWith(ANNOUNCE_PREFIX)) return;
<<<<<<< HEAD:src/main/java/com/dqms/network/UDPDiscoveryService.java
        String[] parts = msg.substring(ANNOUNCE_PREFIX.length()).split(":");
        if (parts.length != 3) return;

        String peerId   = parts[0];
        int    peerPort;
        try { peerPort = Integer.parseInt(parts[1]); } catch (Exception e) { return; }
        boolean peerIsAdmin = "ADMIN".equalsIgnoreCase(parts[2]);

        if (peerId.equals(nodeId)) return;

=======

        String[] parts = msg.substring(ANNOUNCE_PREFIX.length()).split(":");
        if (parts.length != 3) {
            LOG.fine("Malformed announcement ignored: " + msg);
            return;
        }

        String  peerId      = parts[0];
        int     peerPort;
        boolean peerIsAdmin;

        try {
            peerPort    = Integer.parseInt(parts[1]);
            peerIsAdmin = "ADMIN".equalsIgnoreCase(parts[2]);
        } catch (NumberFormatException e) {
            LOG.fine("Malformed port in announcement: " + msg);
            return;
        }

        // Ignore our own announcements — we don't need to track ourselves.
        if (peerId.equals(nodeId)) return;

        // Always refresh the heartbeat timestamp, even for known peers.
        lastSeen.put(peerId, System.currentTimeMillis());

>>>>>>> acdbfea (	modified:   dqms-mvp/src/main/java/com/dqms/app/Main.java):dqms-mvp/src/main/java/com/dqms/network/UDPDiscoveryService.java
        if (!peers.containsKey(peerId)) {
            // Use 127.0.0.1 if sender is from this machine to avoid routing issues
            String targetIp = senderIp;
            if (senderIp.equals("0:0:0:0:0:0:0:1")) targetIp = "127.0.0.1";

            NodeInfo peer = new NodeInfo(peerId, targetIp, peerPort);
            peers.put(peerId, peer);
            LOG.info("Discovered new peer: " + peer);
            if (onPeerFound != null) onPeerFound.accept(peer);
        }
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    /**
     * Signals shutdown and immediately closes the live socket.
     * Closing the socket unblocks {@code socket.receive()} with a
     * {@link SocketException}, allowing the run() loop to exit cleanly
     * without waiting for the next incoming packet.
     */
    public void stop() {
        running = false;
        MulticastSocket s = liveSocket;
        if (s != null && !s.isClosed()) s.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void safeLeaveAndClose(MulticastSocket socket) {
        if (socket == null || socket.isClosed()) return;
        try {
            InetAddress       group   = InetAddress.getByName(MULTICAST_GROUP);
            InetSocketAddress groupSA = new InetSocketAddress(group, UDP_PORT);
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                try { socket.leaveGroup(groupSA, ifaces.nextElement()); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        socket.close();
    }

    /**
     * Normalizes IPv6 loopback addresses to the canonical IPv4 loopback so
     * that same-machine peer connections route correctly.
     */
    private String normalizeIp(String ip) {
        return "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) ? "127.0.0.1" : ip;
    }

    private void startDaemon(String name, Runnable task) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
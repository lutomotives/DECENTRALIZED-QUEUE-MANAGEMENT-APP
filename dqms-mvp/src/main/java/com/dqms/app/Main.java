package com.dqms.app;

import com.dqms.db.DatabaseManager;
import com.dqms.model.Message;
import com.dqms.model.NodeInfo;
import com.dqms.network.TCPClient;
import com.dqms.network.TCPServer;
import com.dqms.network.UDPDiscoveryService;
import com.dqms.queue.QueueManager;
import com.dqms.ui.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Application entry point.
 *
 * Usage:
 *   mvn javafx:run -Djavafx.args="5001 NODE_001"   ← Node 1
 *   mvn javafx:run -Djavafx.args="5002 NODE_002"   ← Node 2 (new terminal)
 *
 * Args:
 *   args[0] — TCP port (e.g. 5001)
 *   args[1] — Node ID  (e.g. NODE_001)
 *
 * Startup sequence:
 *   1. Start TCPServer (accept incoming peer connections)
 *   2. Start UDPDiscovery (announce self, collect peers)
 *   3. Wait 3s for peer responses
 *   4. If peers found → send SYNC_REQUEST to first peer
 *   5. If no peers   → load from local SQLite DB
 *   6. Launch JavaFX UI
 */
public class Main extends Application {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    // Shared state passed between network and UI layers
    public static QueueManager queueManager;
    public static String       nodeId;
    public static int          tcpPort;
    public static boolean      isAdmin = true;  // Default to true; adjust as needed

    @Override
    public void start(Stage stage) throws Exception {
        // Parse args (JavaFX passes them via getParameters())
        var params = getParameters().getRaw();
        tcpPort = params.size() > 0 ? Integer.parseInt(params.get(0)) : 5001;
        nodeId  = params.size() > 1 ? params.get(1) : "NODE_001";

        LOG.info("=== Starting DQMS Node: " + nodeId + " on port " + tcpPort + " ===");

        // ── 1. Init core components ──────────────────────────────────────────
        DatabaseManager db     = new DatabaseManager(nodeId);
        TCPClient       client = new TCPClient();
        Map<String, NodeInfo> peers = new ConcurrentHashMap<>();

        queueManager = new QueueManager(nodeId, db, client, peers, isAdmin, tcpPort);

        // ── 2. Start TCP server ──────────────────────────────────────────────
        TCPServer server = new TCPServer(tcpPort, queueManager);
        Thread serverThread = new Thread(server, "tcp-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // ── 3. Start UDP discovery ───────────────────────────────────────────
        UDPDiscoveryService discovery = new UDPDiscoveryService(
                nodeId, tcpPort, isAdmin, peers,
                peer -> {
                    // Called when a NEW peer is discovered for the first time
                    LOG.info("New peer found: " + peer + " — sending SYNC_REQUEST");
                    Message response = client.requestSync(peer, nodeId, isAdmin, tcpPort);
                    if (response != null && response.getTicketList() != null) {
                        queueManager.applySyncResponse(response.getTicketList());
                    }
                },
                null  // onPeerLost callback (not used here)
        );
        Thread discoveryThread = new Thread(discovery, "udp-discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();

        // ── 4. Wait for peer discovery ───────────────────────────────────────
        LOG.info("Waiting 3s for peer discovery...");
        Thread.sleep(3000);

        // ── 5. If no peers found, load from local DB (first node) ────────────
        if (peers.isEmpty()) {
            LOG.info("No peers found — loading from local database");
            queueManager.loadFromDatabase();
        }

        // ── 6. Launch JavaFX UI ──────────────────────────────────────────────
        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/com/dqms/ui/main.fxml"));
        Scene scene = new Scene(loader.load(), 860, 620);

        // Apply stylesheet
        scene.getStylesheets().add(
                getClass().getResource("/com/dqms/ui/style.css").toExternalForm());

        MainController controller = loader.getController();
        controller.init(queueManager);

        stage.setTitle("DQMS — " + nodeId + " (port " + tcpPort + ")");
        stage.setScene(scene);
        stage.show();

        LOG.info("UI launched for " + nodeId);
    }

    public static void main(String[] args) {
        launch(args);
    }
}

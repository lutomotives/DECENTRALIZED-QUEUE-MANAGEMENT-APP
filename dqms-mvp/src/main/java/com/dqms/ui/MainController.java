package com.dqms.ui;

import com.dqms.model.Ticket;
import com.dqms.queue.QueueManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * JavaFX controller for the admin dashboard.
 *
 * Two-way binding:
 *   QueueManager notifies this controller via setOnQueueChanged callback
 *   → refreshTable() is called on the JavaFX thread via Platform.runLater()
 */
public class MainController {

    // ── FXML bindings ─────────────────────────────────────────────────────────
    @FXML private Label     nodeLabel;
    @FXML private Label     statusLabel;
    @FXML private TextField regNumberField;
    @FXML private TextField studentNameField;
    @FXML private Button    joinButton;
    @FXML private Button    clearBtn;
    @FXML private Label     joinFeedback;
    @FXML private Label     waitingCount;
    @FXML private Label     clearedCount;
    @FXML private Label     peerCount;
    @FXML private Label     syncLog;

    @FXML private TableView<Ticket>          queueTable;
    @FXML private TableColumn<Ticket,Number> posCol;
    @FXML private TableColumn<Ticket,String> regCol;
    @FXML private TableColumn<Ticket,String> nameCol;
    @FXML private TableColumn<Ticket,String> nodeCol;
    @FXML private TableColumn<Ticket,String> timeCol;
    @FXML private TableColumn<Ticket,String> statusCol;

    private QueueManager queueManager;
    private final ObservableList<Ticket> tableData = FXCollections.observableArrayList();
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // ── Initialization ────────────────────────────────────────────────────────

    /**
     * Called by Main.java after the FXML is loaded.
     */
    public void init(QueueManager qm) {
        this.queueManager = qm;

        // Set node label
        nodeLabel.setText(qm.getNodeId());

        // Configure table columns
        posCol.setCellValueFactory(cd -> {
            int idx = tableData.indexOf(cd.getValue()) + 1;
            return new SimpleIntegerProperty(idx);
        });
        regCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getRegistrationNumber()));
        nameCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getStudentName()));
        nodeCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getOriginNodeId()));
        timeCol.setCellValueFactory(cd ->
                new SimpleStringProperty(TIME_FMT.format(
                        Instant.ofEpochMilli(cd.getValue().getIssueTime()))));
        statusCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getStatus()));

        // Color-code status column
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(status);
                    if ("WAITING".equals(status)) {
                        setStyle("-fx-text-fill: #C07000; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #1A7F4B; -fx-font-weight: bold;");
                    }
                }
            }
        });

        queueTable.setItems(tableData);

        // Enable "Mark Cleared" only when a WAITING row is selected AND current node is authorized
        queueTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            clearBtn.setDisable(!canClear(sel));
        });

        // Register callback for when queue changes (called from network threads)
        queueManager.setOnQueueChanged(() ->
                Platform.runLater(this::refreshTable));

        // Auto-refresh peer status every 2s
        Timeline peerRefresh = new Timeline(
                new KeyFrame(Duration.seconds(2), e -> refreshPeerStatus()));
        peerRefresh.setCycleCount(Timeline.INDEFINITE);
        peerRefresh.play();

        // Initial load
        refreshTable();
    }

    // ── Event Handlers ────────────────────────────────────────────────────────

    @FXML
    private void onJoinQueue() {
        String reg  = regNumberField.getText().trim();
        String name = studentNameField.getText().trim();

        if (reg.isEmpty() || name.isEmpty()) {
            joinFeedback.setStyle("-fx-text-fill: #CC0000;");
            joinFeedback.setText("Please fill in both fields.");
            return;
        }

        Ticket t = queueManager.createTicket(reg, name);
        if (t == null) {
            joinFeedback.setStyle("-fx-text-fill: #CC0000;");
            joinFeedback.setText("Error: You already have an active ticket.");
            return;
        }

        // Find position in queue
        List<Ticket> waiting = queueManager.getWaitingTickets();
        int pos = waiting.indexOf(t) + 1;

        joinFeedback.setStyle("-fx-text-fill: #1A7F4B;");
        joinFeedback.setText("✓ Joined queue! Position: #" + pos);

        regNumberField.clear();
        studentNameField.clear();

        // Refresh to disable inputs
        refreshTable();

        log("New ticket created: " + reg + " (pos #" + pos + ")");
    }

    @FXML
    private void onClearSelected() {
        Ticket selected = queueTable.getSelectionModel().getSelectedItem();
        if (selected == null || !"WAITING".equals(selected.getStatus())) return;

        queueManager.clearStudent(selected.getTicketId());
        clearBtn.setDisable(true);
        log("Cleared: " + selected.getRegistrationNumber() + " by " + queueManager.getNodeId());
    }

    @FXML
    private void onRefresh() {
        refreshTable();
        log("Manual refresh at " + TIME_FMT.format(Instant.now()));
    }

    // ── UI Update Helpers ─────────────────────────────────────────────────────

    private void refreshTable() {
        List<Ticket> all = queueManager.getAllTicketsAsList();
        tableData.setAll(all);

        long waiting = all.stream().filter(t -> "WAITING".equals(t.getStatus())).count();
        long cleared = all.stream().filter(t -> "CLEARED".equals(t.getStatus())).count();

        waitingCount.setText(String.valueOf(waiting));
        clearedCount.setText(String.valueOf(cleared));

        // Enforce one-ticket-per-node: disable join inputs if node has active ticket
        boolean hasActive = queueManager.hasActiveTicket();
        joinButton.setDisable(hasActive);
        regNumberField.setDisable(hasActive);
        studentNameField.setDisable(hasActive);
        if (hasActive) {
            joinFeedback.setText("You are currently in the queue.");
            joinFeedback.setStyle("-fx-text-fill: #1A7F4B;");
        }

        // Re-check clear button state
        Ticket sel = queueTable.getSelectionModel().getSelectedItem();
        clearBtn.setDisable(!canClear(sel));
    }

    private boolean canClear(Ticket ticket) {
        if (ticket == null || !"WAITING".equals(ticket.getStatus())) return false;

        // Admin (NODE_001) can clear ANY ticket
        if (queueManager.isAdmin()) return true;

        // Regular nodes can only clear their OWN tickets
        return ticket.getOriginNodeId().equalsIgnoreCase(queueManager.getNodeId());
    }

    private void refreshPeerStatus() {
        int count = queueManager.getPeerCount();
        peerCount.setText(String.valueOf(count));
        statusLabel.setText(count > 0
                ? "● " + count + " peer(s) connected"
                : "○ No peers — standalone mode");
        statusLabel.setStyle(count > 0
                ? "-fx-text-fill: #90EE90;"
                : "-fx-text-fill: #FFB74D;");
    }

    private void log(String msg) {
        syncLog.setText("[" + TIME_FMT.format(Instant.now()) + "] " + msg);
    }
}

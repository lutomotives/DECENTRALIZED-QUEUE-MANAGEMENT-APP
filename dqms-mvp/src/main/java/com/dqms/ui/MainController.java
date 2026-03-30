package com.dqms.ui;

import com.dqms.app.Main;
import com.dqms.model.Ticket;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.logging.Logger;

/**
 * JavaFX controller for the main UI.
 */
public class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());

    @FXML
    private ListView<String> ticketListView;

    @FXML
    private TextField registrationField;

    private ObservableList<String> ticketItems = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        ticketListView.setItems(ticketItems);
    }

    private QueueManager queueManager;

    public void init(QueueManager queueManager) {
        this.queueManager = queueManager;
        refreshTicketList();
    }

    @FXML
    private void handleAddTicket() {
        String regNum = registrationField.getText().trim();
        String name = nameField.getText().trim();
        if (!regNum.isEmpty() && !name.isEmpty() && queueManager != null) {
            queueManager.addTicket(regNum, name);
            registrationField.clear();
            nameField.clear();
            refreshTicketList();
        }
    }

    @FXML
    private void handleClearNext() {
        if (queueManager != null) {
            queueManager.clearNextTicket();
            refreshTicketList();
        }
    }

    private void refreshTicketList() {
        Platform.runLater(() -> {
            ticketItems.clear();
            if (queueManager != null) {
                List<Ticket> tickets = queueManager.getAllTickets();
                for (Ticket ticket : tickets) {
                    ticketItems.add(ticket.toString());
                }
            }
        });
    }
}
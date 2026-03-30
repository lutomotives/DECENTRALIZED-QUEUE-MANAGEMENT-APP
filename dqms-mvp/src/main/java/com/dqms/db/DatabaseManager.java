package com.dqms.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.dqms.model.Ticket;

/**
 * Manages the local SQLite database for persisting queue state.
 */
public class DatabaseManager {

    private static final Logger LOG = Logger.getLogger(DatabaseManager.class.getName());

    private final String dbPath;

    public DatabaseManager(String nodeId) {
        this.dbPath = "dqms_" + nodeId + ".db";
        initializeDatabase();
    }

    private void initializeDatabase() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            // Create tickets table if not exists
            String sql = "CREATE TABLE IF NOT EXISTS tickets (" +
                         "ticket_id TEXT PRIMARY KEY," +
                         "registration_number TEXT," +
                         "student_name TEXT," +
                         "issue_time INTEGER," +
                         "origin_node_id TEXT," +
                         "status TEXT)";
            stmt.execute(sql);
            LOG.info("Database initialized: " + dbPath);
        } catch (SQLException e) {
            LOG.severe("Failed to initialize database: " + e.getMessage());
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    public void saveTicket(Ticket ticket) {
        String sql = "INSERT OR REPLACE INTO tickets (ticket_id, registration_number, student_name, issue_time, origin_node_id, status) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, ticket.getTicketId());
            pstmt.setString(2, ticket.getRegistrationNumber());
            pstmt.setString(3, ticket.getStudentName());
            pstmt.setLong(4, ticket.getIssueTime());
            pstmt.setString(5, ticket.getOriginNodeId());
            pstmt.setString(6, ticket.getStatus());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            LOG.severe("Failed to save ticket: " + e.getMessage());
        }
    }

    public List<Ticket> loadAllTickets() {
        List<Ticket> tickets = new ArrayList<>();
        String sql = "SELECT * FROM tickets ORDER BY issue_time";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Ticket ticket = new Ticket(
                    rs.getString("registration_number"),
                    rs.getString("student_name"),
                    rs.getLong("issue_time"),
                    rs.getString("origin_node_id")
                );
                ticket.setStatus(rs.getString("status"));
                tickets.add(ticket);
            }
        } catch (SQLException e) {
            LOG.severe("Failed to load tickets: " + e.getMessage());
        }
        return tickets;
    }

    public void updateTicketStatus(String ticketId, String newStatus) {
        String sql = "UPDATE tickets SET status = ? WHERE ticket_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newStatus);
            pstmt.setString(2, ticketId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            LOG.severe("Failed to update ticket status: " + e.getMessage());
        }
    }
}
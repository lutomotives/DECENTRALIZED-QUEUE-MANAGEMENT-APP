package com.dqms.db;

import com.dqms.model.Ticket;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages the local SQLite database for this node.
 * Each node keeps its own dqms_<nodeId>.db file.
 *
 * Schema:
 *   tickets(ticket_id PK, registration_number, student_name, issue_time, origin_node, status)
 */
public class DatabaseManager {

    private static final Logger LOG = Logger.getLogger(DatabaseManager.class.getName());
    private final Connection conn;

    public DatabaseManager(String nodeId) throws SQLException {
        String dbPath = "dqms_" + nodeId + ".db";
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        LOG.info("Connected to SQLite: " + dbPath);
        initSchema();
    }

    private void initSchema() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS tickets (
                    ticket_id           TEXT PRIMARY KEY,
                    registration_number TEXT NOT NULL,
                    student_name        TEXT NOT NULL,
                    issue_time          INTEGER NOT NULL,
                    origin_node         TEXT NOT NULL,
                    status              TEXT NOT NULL DEFAULT 'WAITING'
                );
                """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Insert a ticket. Uses INSERT OR IGNORE to safely handle duplicates
     * that arrive via network replication.
     */
    public void insertTicket(Ticket t) {
        String sql = """
                INSERT OR IGNORE INTO tickets
                    (ticket_id, registration_number, student_name, issue_time, origin_node, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.getTicketId());
            ps.setString(2, t.getRegistrationNumber());
            ps.setString(3, t.getStudentName());
            ps.setLong(4, t.getIssueTime());
            ps.setString(5, t.getOriginNodeId());
            ps.setString(6, t.getStatus());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("insertTicket failed: " + e.getMessage());
        }
    }

    /**
     * Update a ticket's status (e.g. WAITING → CLEARED).
     */
    public void updateStatus(String ticketId, String status) {
        String sql = "UPDATE tickets SET status = ? WHERE ticket_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, ticketId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("updateStatus failed: " + e.getMessage());
        }
    }

    /**
     * Check if a ticket already exists — used to prevent replication duplicates.
     */
    public boolean ticketExists(String ticketId) {
        String sql = "SELECT 1 FROM tickets WHERE ticket_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ticketId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            LOG.warning("ticketExists check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Load all tickets from the database — called on node startup to
     * rebuild the in-memory PriorityQueue.
     */
    public List<Ticket> getAllTickets() {
        List<Ticket> tickets = new ArrayList<>();
        String sql = "SELECT * FROM tickets ORDER BY issue_time ASC";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Ticket t = new Ticket(
                        rs.getString("registration_number"),
                        rs.getString("student_name"),
                        rs.getLong("issue_time"),
                        rs.getString("origin_node")
                );
                t.setStatus(rs.getString("status"));
                tickets.add(t);
            }
        } catch (SQLException e) {
            LOG.warning("getAllTickets failed: " + e.getMessage());
        }
        return tickets;
    }

    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}

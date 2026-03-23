package com.dqms.db;

import com.dqms.model.Ticket;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages the local SQLite database for this node.
 */
public class DatabaseManager {

    private static final Logger LOG = Logger.getLogger(DatabaseManager.class.getName());
    private Connection conn;
    private final String dbName;

    public DatabaseManager(String nodeId) {
        this.dbName = "dqms_" + nodeId + ".db";
        try {
            File dbFile = new File(dbName);
            LOG.info(">>> DATABASE FILE: " + dbFile.getAbsolutePath());
            if (!dbFile.exists()) {
                LOG.info(">>> Database file does not exist. A new one will be created.");
            } else {
                LOG.info(">>> Database file exists. Size: " + dbFile.length() + " bytes");
            }
            
            String url = "jdbc:sqlite:" + dbName;
            conn = DriverManager.getConnection(url);
            
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA busy_timeout = 5000;");
            }
            
            createTable();
        } catch (SQLException e) {
            LOG.severe("Database connection failed: " + e.getMessage());
        }
    }

    private void createTable() throws SQLException {
        // First check if the table exists and has the correct schema
        boolean needsRecreation = false;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(tickets)")) {
            boolean hasTicketId = false;
            boolean hasIssueTime = false;
            while (rs.next()) {
                String colName = rs.getString("name");
                if ("ticketId".equals(colName)) hasTicketId = true;
                if ("issueTime".equals(colName)) hasIssueTime = true;
            }
            // If table exists but lacks critical new columns from our schema updates, we must recreate it.
            // (A production app would use ALTER TABLE, but for this MVP, recreation is safer).
            if (hasTicketId || hasIssueTime) { // Actually, if it HAS them it's likely fine. Wait, if it exists but DOESN'T have them, recreate.
                // Re-evaluating: If the table exists (rs has rows) but lacks 'ticketId', it's corrupt/old.
            }
            
            // To be safe, just run a quick query to see if it throws an error
            try {
                 stmt.executeQuery("SELECT ticketId, issueTime FROM tickets LIMIT 1");
            } catch (SQLException e) {
                 if (e.getMessage().contains("no such table")) {
                     // normal, needs creation
                 } else {
                     LOG.warning(">>> DB SCHEMA MISMATCH DETECTED. Recreating table to fix corruption.");
                     needsRecreation = true;
                 }
            }
        } catch (SQLException e) {
             // Ignore
        }

        if (needsRecreation) {
             try (Statement stmt = conn.createStatement()) {
                 stmt.execute("DROP TABLE IF EXISTS tickets");
             }
        }

        String sql = "CREATE TABLE IF NOT EXISTS tickets (" +
                     "ticketId TEXT PRIMARY KEY, " +
                     "registrationNumber TEXT, " +
                     "studentName TEXT, " +
                     "issueTime INTEGER, " +
                     "originNodeId TEXT, " +
                     "status TEXT" +
                     ");";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    public synchronized void insertTicket(Ticket t) {
        String sql = "INSERT OR IGNORE INTO tickets (ticketId, registrationNumber, studentName, issueTime, originNodeId, status) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, t.getTicketId());
            pstmt.setString(2, t.getRegistrationNumber());
            pstmt.setString(3, t.getStudentName());
            pstmt.setLong(4, t.getIssueTime());
            pstmt.setString(5, t.getOriginNodeId());
            pstmt.setString(6, t.getStatus());
            int affected = pstmt.executeUpdate();
            if (affected > 0) {
                LOG.info(">>> DB INSERT SUCCESS: " + t.getTicketId());
            } else {
                LOG.info(">>> DB INSERT IGNORED (Duplicate): " + t.getTicketId());
            }
        } catch (SQLException e) {
            LOG.warning("insertTicket failed: " + e.getMessage());
        }
    }

    public synchronized void updateStatus(String ticketId, String newStatus) {
        String sql = "UPDATE tickets SET status = ? WHERE ticketId = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newStatus);
            pstmt.setString(2, ticketId);
            int affected = pstmt.executeUpdate();
            LOG.info(">>> DB UPDATE: " + ticketId + " -> " + newStatus + " (Rows affected: " + affected + ")");
        } catch (SQLException e) {
            LOG.warning("updateStatus failed: " + e.getMessage());
        }
    }

    public synchronized boolean ticketExists(String ticketId) {
        String sql = "SELECT 1 FROM tickets WHERE ticketId = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, ticketId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized List<Ticket> getAllTickets() {
        List<Ticket> tickets = new ArrayList<>();
        if (conn == null) {
            LOG.severe(">>> DB GET ALL FAILED: Connection is NULL");
            return tickets;
        }
        
        String sql = "SELECT * FROM tickets ORDER BY issueTime ASC";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            int count = 0;
            while (rs.next()) {
                Ticket t = new Ticket(
                        rs.getString("registrationNumber"),
                        rs.getString("studentName"),
                        rs.getLong("issueTime"),
                        rs.getString("originNodeId")
                );
                t.setStatus(rs.getString("status"));
                tickets.add(t);
                count++;
            }
            LOG.info(">>> DB GET ALL: Found " + count + " entries in " + dbName);
        } catch (SQLException e) {
            LOG.warning("getAllTickets failed: " + e.getMessage());
        }
        return tickets;
    }

    public void close() {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }
}

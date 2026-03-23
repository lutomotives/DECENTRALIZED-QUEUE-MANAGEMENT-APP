import java.sql.*;

public class CheckDB {
    public static void main(String[] args) throws Exception {
        String[] dbs = {"dqms-mvp/dqms_NODE_001.db", "dqms-mvp/dqms_NODE_002.db", "dqms-mvp/dqms_OFFICER.db"};
        
        for (String dbName : dbs) {
            System.out.println("Checking " + dbName);
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbName);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM tickets")) {
                int count = 0;
                while (rs.next()) {
                    System.out.println(" - ID: " + rs.getString("ticketId") + ", Origin: " + rs.getString("originNodeId") + ", Status: " + rs.getString("status"));
                    count++;
                }
                System.out.println("Total rows in " + dbName + ": " + count);
            } catch (Exception e) {
                System.out.println("Failed to read " + dbName + ": " + e.getMessage());
            }
            System.out.println("-------------------------------------------------");
        }
    }
}

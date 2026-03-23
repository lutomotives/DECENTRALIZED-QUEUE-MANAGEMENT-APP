# DQMS MVP — Decentralized Queue Management System

## Chuka University | Computer Science Group Project

---

## What This MVP Demonstrates

Two or more fully independent nodes running on **one machine**, discovering each
other via UDP multicast and syncing queue state over TCP sockets. 

This system features **Role-Based Access**:
- **Admin Nodes (e.g., OFFICER):** Can see the entire queue and are the *only* nodes authorized to clear tickets.
- **Regular Nodes (e.g., STUDENT):** Can join the queue but can only view their own tickets. They cannot clear tickets.
- **Global Awareness:** All nodes display accurate, real-time aggregate stats (total waiting, total cleared) and global queue positions.
- **Restrictions:** A regular node can only have one active ticket in the queue at any given time.

### Key Features
- **Instant Synchronization:** When an Admin clears a student, the update is instantly broadcasted and reflects on all connected nodes.
- **Fault Tolerance:** If a node crashes or is closed, the rest of the network continues uninterrupted.
- **Data Persistence:** Every node maintains its own local SQLite database (`.db`). When a node restarts, it loads its history and requests a fresh SYNC from the network.
- **Self-Healing Schema:** The database automatically detects schema changes and recreates tables to prevent corruption.

---

## Prerequisites

| Tool                        | Version | Download                          |
| --------------------------- | ------- | --------------------------------- |
| JDK                         | 17+     | https://adoptium.net              |
| Maven                       | 3.8+    | https://maven.apache.org/download |

---

## Quick Start (Running on One Machine)

### Step 1 — Open TWO terminal windows in the project root directory.

### Step 2 — Build the project (first time or after changes)

```bash
mvn clean compile
```

### Step 3 — Start the Admin Node (Terminal 1)

*Note the space after `-D` and the `ADMIN` flag at the end.*

```bash
mvn javafx:run -D javafx.args="5001 OFFICER ADMIN"
```

### Step 4 — Start a Regular Node (Terminal 2)

```bash
mvn javafx:run -D javafx.args="5002 STUDENT_01"
```

The Student Node will:
1. Discover the Admin via UDP multicast.
2. Send a `SYNC_REQUEST` to the Admin.
3. Receive all its existing tickets in a `SYNC_RESPONSE`.
4. Display the synced queue in its UI.

---

## Demo Script (Show This to Your Supervisor)

### Scene 1: Joining the Queue & Global Stats
1. Start the Admin (`OFFICER`) and a Regular Node (`STUDENT_01`).
2. On the **Student Node**: enter a registration number + name, and click "Join Queue".
3. Notice the UI prevents the student from joining again ("You are currently in the queue").
4. Look at the **Admin Node**: The new ticket appears instantly in the table. Both nodes show "Waiting: 1" at the top.

### Scene 2: Admin Clearance Propagation
1. On the **Admin Node**: select the WAITING student in the table and click "Mark Cleared".
2. Watch the **Student Node** update instantly — the global stats change to "Waiting: 0, Cleared: 1", and the student's local view shows their ticket as CLEARED.

### Scene 3: Fault Tolerance & Data Persistence
1. Close the Student Node.
2. Restart the Student Node using the exact same command.
3. It instantly connects to the Admin, requests a sync, and its history (the cleared ticket) appears without any data loss.
4. Check the project folder: You will see `dqms_OFFICER.db` and `dqms_STUDENT_01.db` representing the localized, decentralized storage.

---

## Project Structure

```text
src/main/
├── java/com/dqms/
│   ├── app/
│   │   └── Main.java                ← Entry point, arg parsing, startup sequence
│   ├── model/
│   │   ├── Ticket.java              ← Core data entity (Serializable)
│   │   ├── Message.java             ← TCP message envelope
│   │   └── NodeInfo.java            ← Peer registry entry
│   ├── db/
│   │   └── DatabaseManager.java     ← SQLite CRUD wrapper with safe-mode reloading
│   ├── network/
│   │   ├── TCPServer.java           ← Accepts peer connections
│   │   ├── TCPClient.java           ← Sends messages to peers
│   │   ├── MessageHandler.java      ← Routes incoming messages & handles sync
│   │   └── UDPDiscoveryService.java ← Multicast peer discovery
│   ├── queue/
│   │   └── QueueManager.java        ← Core business logic, memory queue, privacy filters
│   └── ui/
│       └── MainController.java      ← JavaFX dashboard controller
└── resources/com/dqms/ui/
    ├── main.fxml                    ← UI layout
    └── style.css                    ← Styling
```

---

## Troubleshooting

| Problem                         | Solution                                                                |
| ------------------------------- | ----------------------------------------------------------------------- |
| Port already in use             | Change 5001/5002 to other ports e.g. 6001/6002                          |
| Nodes don't discover each other | Ensure both run on same machine; check firewall isn't blocking UDP 4446 |
| DB Errors / Blank UI            | Stop all nodes, delete the `*.db` files, and restart.                   |
| `ClassNotFoundException`        | Run: `mvn clean compile`                                                |

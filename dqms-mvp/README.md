# DQMS MVP — Decentralized Queue Management System
## Chuka University | Computer Science Group Project

---

## What This MVP Demonstrates

Two fully independent nodes running on **one machine**, discovering each
other via UDP multicast on localhost, and syncing queue state over TCP sockets.

- Student joins queue on Node 1 → Node 2 sees it immediately
- Admin clears a student on Node 2 → Node 1 reflects the update
- Close Node 1 → Node 2 keeps running (fault tolerance)
- Restart Node 1 → it requests a SYNC from Node 2 and rebuilds its queue

---

## Prerequisites

| Tool    | Version  | Download                          |
|---------|----------|-----------------------------------|
| JDK     | 17+      | https://adoptium.net              |
| Maven   | 3.8+     | https://maven.apache.org/download |
| IntelliJ IDEA (recommended) | Any | https://www.jetbrains.com/idea |

---

## Quick Start (2 Nodes on One Machine)

### Step 1 — Open TWO terminal windows

### Step 2 — Build the project (first time only)
```bash
cd dqms-mvp
mvn clean package -DskipTests
```

### Step 3 — Start Node 1 (Terminal 1)
```bash
mvn javafx:run -Djavafx.args="5001 NODE_001"
```

### Step 4 — Start Node 2 (Terminal 2, wait ~5 seconds after Node 1)
```bash
mvn javafx:run -Djavafx.args="5002 NODE_002"
```

Node 2 will:
1. Discover Node 1 via UDP multicast
2. Send a SYNC_REQUEST to Node 1
3. Receive all existing tickets in a SYNC_RESPONSE
4. Display the synced queue in its UI

---

## Running in IntelliJ IDEA

1. Open the `dqms-mvp` folder as a Maven project
2. Let IntelliJ import dependencies (wait for Maven sync)
3. Create two Run Configurations:

**Run Config 1 — Node 1:**
- Main class: `com.dqms.app.Main`
- Program arguments: `5001 NODE_001`
- VM options: `--module-path $JAVAFX_HOME/lib --add-modules javafx.controls,javafx.fxml`

**Run Config 2 — Node 2:**
- Main class: `com.dqms.app.Main`
- Program arguments: `5002 NODE_002`

4. Run Config 1 first, then Run Config 2

---

## Demo Script (Show This to Your Supervisor)

### Scene 1: Normal P2P Sync
1. Start Node 1 and Node 2
2. On **Node 1**: enter reg number + name, click "Join Queue"
3. Watch **Node 2** update automatically (within ~1 second)
4. On **Node 2**: enter a different student, click "Join Queue"
5. Watch **Node 1** update — FIFO order is maintained across both nodes

### Scene 2: Admin Clearance Propagation
1. On **Node 1**: select a WAITING student, click "Mark Cleared"
2. Watch **Node 2** update — student shows as CLEARED on both dashboards

### Scene 3: Fault Tolerance (Node Failure)
1. Close Node 1 (Ctrl+C or close window)
2. On **Node 2**: add more students — it keeps working alone
3. Restart Node 1: `mvn javafx:run -Djavafx.args="5001 NODE_001"`
4. Node 1 rediscovers Node 2, requests SYNC, and rebuilds the full queue

### Scene 4: Data Persistence
1. Close BOTH nodes
2. Check the generated `.db` files: `dqms_NODE_001.db`, `dqms_NODE_002.db`
3. Restart Node 1 — it loads all tickets from its local SQLite file

---

## Project Structure

```
dqms-mvp/
├── pom.xml
└── src/main/
    ├── java/com/dqms/
    │   ├── app/
    │   │   └── Main.java              ← Entry point, startup sequence
    │   ├── model/
    │   │   ├── Ticket.java            ← Core data entity (Serializable)
    │   │   ├── Message.java           ← TCP message envelope
    │   │   └── NodeInfo.java          ← Peer registry entry
    │   ├── db/
    │   │   └── DatabaseManager.java   ← SQLite CRUD wrapper
    │   ├── network/
    │   │   ├── TCPServer.java         ← Accepts peer connections
    │   │   ├── TCPClient.java         ← Sends messages to peers
    │   │   ├── MessageHandler.java    ← Routes incoming messages
    │   │   └── UDPDiscoveryService.java ← Multicast peer discovery
    │   ├── queue/
    │   │   └── QueueManager.java      ← Core business logic
    │   └── ui/
    │       └── MainController.java    ← JavaFX dashboard controller
    └── resources/com/dqms/ui/
        ├── main.fxml                  ← UI layout
        └── style.css                  ← Styling
```

---

## Architecture at a Glance

```
┌──────────────────────┐         UDP Multicast (230.0.0.0:4446)         ┌──────────────────────┐
│      NODE_001        │ ◄─────────────────────────────────────────────► │      NODE_002        │
│    port 5001         │                                                  │    port 5002         │
│                      │         TCP Sockets (direct connection)          │                      │
│  JavaFX Dashboard    │ ◄─────────────────────────────────────────────► │  JavaFX Dashboard    │
│  QueueManager        │                                                  │  QueueManager        │
│  SQLite DB           │                                                  │  SQLite DB           │
│  (dqms_NODE_001.db)  │                                                  │  (dqms_NODE_002.db)  │
└──────────────────────┘                                                  └──────────────────────┘
```

---

## Troubleshooting

| Problem | Solution |
|---------|---------|
| Port already in use | Change 5001/5002 to other ports e.g. 6001/6002 |
| Nodes don't discover each other | Ensure both run on same machine; check firewall isn't blocking UDP 4446 |
| `ClassNotFoundException` | Clean and rebuild: `mvn clean package` |
| SQLite error on startup | Delete the `.db` files to start fresh |
| JavaFX not found | Add `--module-path` VM option pointing to your JavaFX SDK lib folder |

---

## Next Steps (Extending the MVP)

- Deploy to multiple machines on the same LAN (change `localhost` → LAN IPs)
- Add password protection to the admin "Mark Cleared" action
- Add encrypted TCP communication (SSL/TLS socket wrapping)
- Add a student kiosk mode (read-only view showing queue position)
- Integrate Hazelcast for more robust group membership management

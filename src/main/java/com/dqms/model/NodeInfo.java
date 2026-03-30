package com.dqms.model;

import java.io.Serializable;

/**
 * Represents a known peer node on the network.
 */
public class NodeInfo implements Serializable {

    private final String nodeId;
    private final String ipAddress;
    private final int tcpPort;
    private final boolean admin;

    public NodeInfo(String nodeId, String ipAddress, int tcpPort) {
        this.nodeId = nodeId;
        this.ipAddress = ipAddress;
        this.tcpPort = tcpPort;
        this.admin = admin;
    }

    public String getNodeId()    { return nodeId; }
    public String getIpAddress() { return ipAddress; }
    public int    getTcpPort()   { return tcpPort; }
    public boolean isAdmin()     { return admin; }

    @Override
    public String toString() {
        return String.format("%s (%s) @ %s:%d", nodeId, admin ? "ADMIN" : "REGULAR", ipAddress, tcpPort);
    }
}

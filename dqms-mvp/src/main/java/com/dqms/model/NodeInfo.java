package com.dqms.model;

/**
 * Represents a known peer node on the network.
 */
public class NodeInfo {

    private final String nodeId;
    private final String ipAddress;
    private final int tcpPort;

    public NodeInfo(String nodeId, String ipAddress, int tcpPort) {
        this.nodeId = nodeId;
        this.ipAddress = ipAddress;
        this.tcpPort = tcpPort;
    }

    public String getNodeId()    { return nodeId; }
    public String getIpAddress() { return ipAddress; }
    public int    getTcpPort()   { return tcpPort; }

    @Override
    public String toString() {
        return nodeId + "@" + ipAddress + ":" + tcpPort;
    }
}
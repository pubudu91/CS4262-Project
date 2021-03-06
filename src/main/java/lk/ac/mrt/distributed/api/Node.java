package lk.ac.mrt.distributed.api;

import java.util.List;
import java.util.Map;

/**
 * @author Chathura Widanage
 */
public class Node {
    protected int port;
    protected String ip;
    protected String username;

    public Node(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    @Override
    public String toString() {
        return ip + ":" + port;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj != null && obj instanceof Node) {
            Node comp = (Node) obj;
            return this.ip.equals(comp) && this.port == comp.port;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return (ip + ":" + port).hashCode();
    }
}

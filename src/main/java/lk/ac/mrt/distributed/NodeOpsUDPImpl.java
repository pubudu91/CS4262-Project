package lk.ac.mrt.distributed;

import lk.ac.mrt.distributed.api.Broadcastable;
import lk.ac.mrt.distributed.api.Node;
import lk.ac.mrt.distributed.api.NodeOps;
import lk.ac.mrt.distributed.api.exceptions.BootstrapException;
import lk.ac.mrt.distributed.api.exceptions.BroadcastException;
import lk.ac.mrt.distributed.api.exceptions.CommunicationException;
import lk.ac.mrt.distributed.api.exceptions.registration.RegistrationException;
import lk.ac.mrt.distributed.api.messages.broadcasts.MasterBroadcast;
import lk.ac.mrt.distributed.api.messages.requests.JoinRequest;
import lk.ac.mrt.distributed.api.messages.requests.LeaveRequest;
import lk.ac.mrt.distributed.api.messages.requests.RegisterRequest;
import lk.ac.mrt.distributed.api.messages.responses.JoinResponse;
import lk.ac.mrt.distributed.api.messages.responses.LeaveResponse;
import lk.ac.mrt.distributed.api.messages.responses.RegisterResponse;
import lk.ac.mrt.distributed.api.messages.responses.UnRegisterResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

/**
 * UDP implementation of node operations
 *
 * @author Chathura Widanage
 */
public class NodeOpsUDPImpl extends NodeOps implements Runnable {
    private final Logger logger = LogManager.getLogger(NodeOpsUDPImpl.class);

    private Node bootstrapServer;

    private DatagramSocket socket;

    //RequestResponse Latches
    private UDPRequestResponseHandler registerRequestResponseHolder;

    private Cache<String, Broadcastable> broadcastableCache;

    public NodeOpsUDPImpl(String bootstrapServerIp, int bootstrapServerPort) {
        this.bootstrapServer = new Node(bootstrapServerIp, bootstrapServerPort);

        this.broadcastableCache = new Cache2kBuilder<String, Broadcastable>() {
        }
                .name("broadcastables")
                .eternal(false)
                .expireAfterWrite(1, TimeUnit.DAYS)
                .entryCapacity(10000).build();
    }

    @Override
    protected void bootstrap() throws BootstrapException {
        try {
            socket = new DatagramSocket(this.selfNode.getPort());
        } catch (SocketException e) {
            throw new BootstrapException(e);
        }
        new Thread(this).start();
    }

    @Override
    public RegisterResponse register() throws CommunicationException, RegistrationException {
        RegisterRequest registerRequest = RegisterRequest.generate(selfNode.getIp(), selfNode.getPort(), selfNode.getUsername());
        try {
            registerRequestResponseHolder = new UDPRequestResponseHandler(bootstrapServer, registerRequest, this);
            registerRequestResponseHolder.send();
            RegisterResponse registerResponse = RegisterResponse.parse(registerRequestResponseHolder.getResponse());
            registerRequestResponseHolder = null;
            return registerResponse;
        } catch (IOException e) {
            e.printStackTrace();
            throw new CommunicationException(e);
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new CommunicationException(e);
        }
    }

    @Override
    public UnRegisterResponse unregister() {
        return null;
    }

    @Override
    public void join(Set<Node> neighbours) {

    }

    @Override
    public void leave(Set<Node> neighbours) {

    }

    @Override
    public void search(String fileName, Set<Node> neighbours) {

    }

    @Override
    public void broadcast(Broadcastable broadcastable, Set<Node> neighbours) throws BroadcastException {
        Broadcastable oldBroadcastable = broadcastableCache.get(broadcastable.getMessageId());
        try {
            if (oldBroadcastable == null || !oldBroadcastable.isBroadcasted()) {
                //narrowcasting to all neighbours -> broadcasting to whole network
                for (Node n : neighbours) {
                    this.send(n, broadcastable.getBroadcastMessage().getBytes());
                }
                oldBroadcastable.setBroadcasted();
                broadcastableCache.put(broadcastable.getMessageId(), broadcastable);
            }
        } catch (IOException ex) {
            logger.error("Failed to broadcast '{}'", broadcastable.getBroadcastMessage(), ex);
            throw new BroadcastException();
        }
    }

    @Override
    public void run() {
        byte buffer[];
        DatagramPacket datagramPacket;
        while (true) {
            buffer = new byte[65536];
            datagramPacket = new DatagramPacket(buffer, buffer.length);
            try {
                logger.info("Waiting for a message...");
                socket.receive(datagramPacket);
                logger.info("New data packet received from {} {}. Data :  {}", datagramPacket.getAddress().toString(),
                        datagramPacket.getPort(),
                        new String(buffer).trim());
                received(datagramPacket);
                //sending ACK //todo implement
                /*send(datagramPacket.getAddress(),
                        datagramPacket.getPort(), "GOT".getBytes());*/
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void received(DatagramPacket datagramPacket) {
        String msg = new String(datagramPacket.getData(), 0, datagramPacket.getLength());
        StringTokenizer stringTokenizer = new StringTokenizer(msg, " ");
        String length = stringTokenizer.nextToken();
        String command = stringTokenizer.nextToken();
        int statusCode = 0;
        try {
            switch (command) {
                case "REGOK":
                    //handle register response
                    logger.info("Received REGOK with message '{}'", msg);
                    if (this.registerRequestResponseHolder != null) {
                        registerRequestResponseHolder.setResponse(msg);
                    }
                    break;
                case "LEAVE":
                    LeaveRequest leaveRequest = LeaveRequest.parse(msg);
                    statusCode = this.commandListener.onLeaveRequest(leaveRequest);
                    LeaveResponse leaveResponse = new LeaveResponse();
                    leaveResponse.setValue(statusCode);
                    send(leaveRequest.getNode(), leaveResponse.getSendableString().getBytes());
                    break;
                case "JOIN":
                    JoinRequest joinRequest = JoinRequest.parse(msg);
                    statusCode = this.commandListener.onJoinRequest(joinRequest);
                    JoinResponse joinResponse = new JoinResponse();
                    joinResponse.setValue(statusCode);
                    send(joinRequest.getNode(), joinResponse.getSendableString().getBytes());
                    break;//todo LEAVEOK, JOINOK
                case "MEMASTER":
                    MasterBroadcast masterBroadcast = MasterBroadcast.parse(msg);
                    commandListener.onMasterBroadcast(masterBroadcast);
                    break;
            }
        } catch (Exception ex) {//todo make this better
            //catching any error in order to not harm the while loop
            logger.error("Error in executing received message", ex);
        }
    }


    public void send(Node node, byte[] msg) throws IOException {
        send(node.getIp(), node.getPort(), msg);
    }

    public void send(String ip, int port, byte[] msg) throws IOException {
        send(InetAddress.getByName(ip), port, msg);
    }

    public void send(InetAddress inetAddress, int port, byte[] msg) throws IOException {
        DatagramPacket datagramPacket = new DatagramPacket(msg, msg.length);
        datagramPacket.setAddress(inetAddress);
        datagramPacket.setPort(port);
        logger.info("Sending message '{}' to {}:{}", new String(msg), inetAddress.getHostName(), port);
        socket.send(datagramPacket);
    }
}

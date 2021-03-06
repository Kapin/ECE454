package ece454750s15a1;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

public class A1ManagementForwarder implements A1Management.Iface {

    private List<DiscoveryInfo> seeds;
    private List<DiscoveryInfo> frontEndNodes;
    private List<DiscoveryInfo> backEndNodes;
    private Map<DiscoveryInfo, Map<Long, TTransport>> openConnections;
    private boolean isSeed = false;
    private Logger logger;
    private long lastUpdated;
    private int numReceived = 0;
    private int numCompleted = 0;
    private long birthTime = 0;
    private Machine roundRobin;
    private Random rng;

    private static final long GOSSIP_FREQUENCY_MILLIS = 100L;
    private static final long GOSSIP_DELAY_MILLIS = 2000L;

    private class Gossiper extends TimerTask {
        @Override
        public void run() {
            // sanity check
            A1ManagementForwarder.this.gossip();
        }
    }

    private class Machine {
        private long maxWork;
        private long workload;
        private DiscoveryInfo info;
        private Machine next;
        private Logger log;

        public Machine(long workCapability, DiscoveryInfo machineInfo, Logger logger) {
            maxWork = workCapability;
            workload = workCapability;
            info = machineInfo;
            next = null;
            log = logger;
        }

        // how many more tasks the machine can take
        public long loadRemaining() {
             return workload;
        }

        public DiscoveryInfo getInfo() {
            return info;
        }

        public Machine getNext() {
            return next;
        }

        public void setNext(Machine nextMachine) {
            next = nextMachine;
        }

        public synchronized Machine assignWork() {
            if (workload > 0) {
                logger.info("Worker queued: " + this.getInfo() + " " + workload);
                --workload;
                return this;
            }
            logger.info("Worker queued: " + this.next.getInfo() + " " + workload);
            workload = maxWork;
            return this.getNext();
        }
    }

    public A1ManagementForwarder(List<DiscoveryInfo> seeds) {
        super();
        this.seeds = seeds;
        roundRobin = null;
        backEndNodes = new Vector<DiscoveryInfo>();
        frontEndNodes = new Vector<DiscoveryInfo>();
        logger = LoggerFactory.getLogger(FEServer.class);
        lastUpdated = 0L;

        Timer gossipSchedule = new Timer(true);
        TimerTask gossip = new Gossiper();
        gossipSchedule.scheduleAtFixedRate(gossip, GOSSIP_DELAY_MILLIS, GOSSIP_FREQUENCY_MILLIS);

        openConnections = new ConcurrentHashMap<DiscoveryInfo, Map<Long, TTransport>>();
        birthTime = System.currentTimeMillis();
        rng = new Random(System.currentTimeMillis());
    }

    public A1ManagementForwarder(List<DiscoveryInfo> seeds, DiscoveryInfo self) {
        this(seeds);
        logger.info("Creating seed node");
        this.seeds.remove(self);
        isSeed = true;
    }

    @Override
    public PerfCounters getPerfCounters() throws TException {
        return new PerfCounters((int)System.currentTimeMillis() - (int)birthTime, numReceived, numCompleted);

    }

    @Override
    public List<String> getGroupMembers() throws ServiceUnavailableException, InvalidNodeException {
        long timestamp = System.currentTimeMillis();
        while(true) {
            DiscoveryInfo backendInfo = this.getRequestNode();

            if (backendInfo == null) {
                throw new ServiceUnavailableException();
            }

            try {
                A1Management.Client backendClient = openClientConnection(backendInfo, timestamp);
                List<String> members = backendClient.getGroupMembers();
                openConnections.get(backendInfo).remove(timestamp).close();
                return members;
            } catch (TException e) {
                logger.warn("Unable to connect to node: " + backendInfo.toString());
                this.reportNode(backendInfo, System.currentTimeMillis());
            }
        }
    }

    @Override
    public boolean registerNode(DiscoveryInfo discoveryInfo) {
        if (!isSeed) {
            return false;
        }

        if (discoveryInfo.isIsBEServer()) {
            logger.info("Registered backend node");
            backEndNodes.add(discoveryInfo);
        } else {
            logger.info("Registered frontend node");
            frontEndNodes.add(discoveryInfo);
        }
        lastUpdated = System.currentTimeMillis();
        roundRobin = generateRoundRobin();
        return true;
    }

    @Override
    public void inform(List<DiscoveryInfo> frontend, List<DiscoveryInfo> backend, long timestamp) throws InvalidNodeException {
        if (timestamp <= lastUpdated) {
            return;
        }

        lastUpdated = timestamp;
        logger.info("" + lastUpdated + " - Received new information about system. Updating cluster state knowledge.");
        this.frontEndNodes = frontend;
        this.backEndNodes = backend;
        roundRobin = generateRoundRobin();
    }

    @Override
    public void reportNode(DiscoveryInfo backend, long timestamp) throws InvalidNodeException {
        if (timestamp <= lastUpdated) {
            return;
        }

        lastUpdated = timestamp;

        if (!isSeed) {
            List<DiscoveryInfo> seedCopy = new Vector<DiscoveryInfo>(seeds);
            for (DiscoveryInfo seed : seeds) {
                try {
                    TTransport transport = new TFramedTransport(new TSocket(seed.getHost(), seed.getMport()));
                    transport.open();
                    TProtocol protocol = new TCompactProtocol(transport);
                    A1Management.Client seedClient = new A1Management.Client(protocol);
                    seedClient.reportNode(backend, timestamp);
                    transport.close();
                } catch(TTransportException e) {
                    seedCopy.remove(seed);
                    logger.warn("Opening Socket Failed");
                    logger.warn("Unable to inform seed node " + seed.toString() + " of bad BE node. Seed node may be down.");
                    logger.info("Removing seed node from list of available nodes.");
                } catch (TException e) {
                    seedCopy.remove(seed);
                    logger.warn("Management client failed");
                    logger.warn("Unable to inform seed node " + seed.toString() + " of bad BE node. Seed node may be down.");
                    logger.info("Removing seed node from list of available nodes.");
                }

                if (seedCopy.isEmpty()) {
                    logger.error("List of available seed nodes is empty. System may not work as expected");
                }
            }
            seeds = seedCopy;
        }

        backEndNodes.remove(backend);
        roundRobin = generateRoundRobin();
    }

    @Override
    public DiscoveryInfo getRequestNode() {
        if (backEndNodes.isEmpty()) {
            return null;
        }

        while (roundRobin == null) {
            roundRobin = generateRoundRobin();
            // wait for additional registrations for a bit then try again
            // only sleep if we don't get the results right away
            if (roundRobin == null) {
                try {
                    Thread.sleep(GOSSIP_DELAY_MILLIS);
                // let it fall through and continue looping
                } catch (InterruptedException e) {
                    logger.warn("Sleep interrupted while getting request node");
                }
            }
        }
        roundRobin = roundRobin.assignWork();
        return roundRobin.getInfo();
    }

    private synchronized Machine generateRoundRobin() {
        if (backEndNodes.isEmpty()) {
            return null;
        }
        long work = (long)Math.ceil(Math.log((double)backEndNodes.get(0).getNcores()));
        Machine start = new Machine(work, backEndNodes.get(0), logger);
        Machine next;
        Machine iter = start;
        for (int i = 1; i < backEndNodes.size(); i++) {
            work = (long)Math.ceil(Math.log((double)backEndNodes.get(i).getNcores()));
            next = new Machine(work, backEndNodes.get(i), logger);
            if (i == 1) {
                start.setNext(next);
            } else {
                iter.setNext(next);
            }
            iter = next;
        }
        iter.setNext(start);
        return start;
    }

    private void gossip() {
        if (!seeds.isEmpty() && !isSeed) {
            DiscoveryInfo seed = seeds.get(rng.nextInt(seeds.size()));
            try {
                TTransport seedTransport = new TFramedTransport(new TSocket(seed.getHost(), seed.getMport()));
                seedTransport.open();
                TProtocol seedProtocol = new TCompactProtocol(seedTransport);
                A1Management.Client seedClient = new A1Management.Client(seedProtocol);
                seedClient.inform(frontEndNodes, backEndNodes, lastUpdated);
                seedTransport.close();
            } catch (Exception e) {
                // TODO: better error handling
                logger.warn("Gossip protocol failed to connect to seed" + seed.getHost() + ":" + seed.getMport());
                e.printStackTrace();
            }
        }
        if (!frontEndNodes.isEmpty()) {
            DiscoveryInfo friend = frontEndNodes.get(rng.nextInt(frontEndNodes.size()));
            try {
                TTransport friendTransport = new TFramedTransport(new TSocket(friend.getHost(), friend.getMport()));
                friendTransport.open();
                TProtocol friendProtocol = new TCompactProtocol(friendTransport);
                A1Management.Client friendClient = new A1Management.Client(friendProtocol);
                friendClient.inform(frontEndNodes, backEndNodes, lastUpdated);
                friendTransport.close();
            } catch (Exception e) {
                // TODO: better error handling
                logger.warn("Gossip protocol failed to connect to friend node " + friend.getHost() + ":" + friend.getMport());
                e.printStackTrace();
            }
        }
    }

    private synchronized A1Management.Client openClientConnection(DiscoveryInfo info, long timestamp) throws TTransportException {
        logger.info("Opening connection with backend node " + info.getHost() + ":" + info.getPport());
        TTransport transport = new TFramedTransport(new TSocket(info.getHost(), info.getMport()));
        transport.open();
        TProtocol protocol = new TCompactProtocol(transport);
        A1Management.Client backendClient = new A1Management.Client(protocol);

        if (!openConnections.containsKey(info)) {
            Map <Long, TTransport> sockets = new ConcurrentHashMap<Long, TTransport>();
            sockets.put(timestamp, transport);
            openConnections.put(info, sockets);
        } else {
            openConnections.get(info).put(timestamp, transport);
        }

        return backendClient;
    }

    public synchronized void receiveRequest() {
        ++numReceived;
    }

    public synchronized void completeRequest() {
        ++numCompleted;
    }
}

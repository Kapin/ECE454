package ece454750s15a1;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TFramedTransport;
import org.slf4j.Logger;

import java.util.List;
import java.util.Vector;

public abstract class Server {
    /**
    *   host: name of host on which this process will run
    *   pport:port number for password service
    *   mport: port number for management service
    *   ncores: number of cores available to the process
    *   seeds: comma-separated list of host:portpairs corresponding to FE seed nodes
    **/

    private String host = "localhost";
    private int pport = -1;
    private int mport = -1;
    private int ncores = -1;
    private List<DiscoveryInfo> seeds;

    protected static int DISCOVERY_TIMEOUT = 10000;

    public enum Options {
        HOST    { public String toString() { return "host"; } },
        PPORT   { public String toString() { return "pport"; } },
        MPORT   { public String toString() { return "mport"; } },
        NCORES  { public String toString() { return "ncores"; } },
        SEEDS   { public String toString() { return "seeds"; } },
    }

    public Server(String [] args) {
        for (int i = 0; i < args.length; i++) {
            String opt = args[i];
            if (opt.endsWith(Options.HOST.toString())) {
                host = args[++i];
            } else if (opt.endsWith(Options.PPORT.toString())) {
                pport = Integer.parseInt(args[++i]);
            } else if (opt.endsWith(Options.MPORT.toString())) {
                mport = Integer.parseInt(args[++i]);
            } else if (opt.endsWith(Options.NCORES.toString())) {
                ncores = Integer.parseInt(args[++i]);
            } else if (opt.endsWith(Options.SEEDS.toString())) {
                createSeeds(args[++i].split(","));
            } else {
                System.out.println("Invalid option " + args[i] + " provided to server. Exiting...");
                System.exit(0);
            }
        }
        boolean errorDetected = false;
        if (ncores <= 0) {
            errorDetected = true;
            System.out.println("Number of cores must be greater than 0.");
        }

        if (pport < 0 || pport > 65535) {
            errorDetected = true;
            System.out.println("Password port must be in viable port range (0-65535)");
        }

        if (mport < 0 || mport > 65535) {
            errorDetected = true;
            System.out.println("Management port must be in viable port range (0-65535)");
        }

        if (mport == pport) {
            errorDetected = true;
            System.out.println("Password port and management port must have different port numbers");
        }

        if (seeds.isEmpty()) {
            errorDetected = true;
            System.out.println("Seed nodes are required for initial node connection.");
        }

        if (errorDetected) {
            System.err.println("Errors detected with command line options. Unable to start server");
            System.exit(0);
        }
    }

    private void createSeeds(String[] rawSeeds) {
        seeds = new Vector<DiscoveryInfo>();
        for (String seed : rawSeeds) {
            String[] parts = seed.split(":");
            String hostname = parts[0];
            int port = new Integer(parts[1]);
            DiscoveryInfo info = new DiscoveryInfo();
            info.setHost(hostname);
            info.setMport(port);
            info.setIsBEServer(false);
            seeds.add(info);
        }
    }

    protected void register(String host, int mPort, Logger logger, DiscoveryInfo registrationNode) {
        try {
            logger.info("Registering with " + host + ":" + mPort);
            TTransport transport = new TFramedTransport(new TSocket(host, mPort));
            transport.open();
            TProtocol protocol = new TCompactProtocol(transport);
            A1Management.Client client = new A1Management.Client(protocol);
            // set timeout to 10 seconds
            // transport.setTimeout(DISCOVERY_TIMEOUT);

            client.registerNode(registrationNode);

            transport.close();
            logger.info("Successfully registered with " + host + ":" + mPort + ".");
        } catch (TException e) {
            logger.warn("Failed to register with " + host + ":" + mPort);
            e.printStackTrace();
        }
    }

    public String getHost() {
        return host;
    }

    public int getPPort() {
        return pport;
    }

    public int getMPort() {
        return mport;
    }

    public int getNCores() {
        return ncores;
    }

    public List<DiscoveryInfo> getSeeds() {
        return seeds;
    }

    protected abstract void start();
}
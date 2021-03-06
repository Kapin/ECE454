package ece454750s15a1;

import org.apache.thrift.TException;

import java.util.Arrays;
import java.util.List;

// generated thrift code

public class A1ManagementHandler implements A1Management.Iface {

    private int numReceived = 0;
    private int numCompleted = 0;
    private long birthTime = 0;

    public A1ManagementHandler() {
        birthTime = System.currentTimeMillis();
    }

    @Override
    public PerfCounters getPerfCounters() throws TException {
        return new PerfCounters((int)System.currentTimeMillis() - (int)birthTime, numReceived, numCompleted);
    }

    @Override
    public List<String> getGroupMembers() throws TException {
        return Arrays.asList("jzanutto", "jkalpin");
    }

    @Override
    public boolean registerNode(DiscoveryInfo discoveryInfo) throws TException {
        throw new InvalidNodeException();
    }

    @Override
    public DiscoveryInfo getRequestNode() throws TException {
        throw new InvalidNodeException();
    }

    @Override
    public void inform(List<DiscoveryInfo> frontend, List<DiscoveryInfo> backend, long timestamp) throws TException, InvalidNodeException {
        throw new InvalidNodeException();
    }

    @Override
    public void reportNode(DiscoveryInfo backendNode, long timestamp) throws TException, InvalidNodeException {
        throw new InvalidNodeException();
    }

    public synchronized void receiveRequest() {
        ++numReceived;
    }

    public synchronized void completeRequest() {
        ++numCompleted;
    }
}

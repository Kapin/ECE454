import org.apache.thrift.TException;
import java.util.*;

// generated thrift code
import ece454750s15a1.*;

public class A1ManagementHandlerAsync implements A1Management.AsyncIface {


    public A1ManagementHandlerAsync() {}

    public void getPerfCounters(AsyncMethodCallback resultHandler) throws TException {

    }

    public void getGroupMembers(AsyncMethodCallback resultHandler) throws TException {
        List<String> members = new ArrayList<String>();
        members.add("jzanutto");
        members.add("jkalpin");
    }


}

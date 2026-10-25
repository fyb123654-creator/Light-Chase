package com.lightchase.network;
import com.lightchase.contracts.ControlStatus;
import java.util.*;
import java.util.function.Consumer;
/** Called under the gateway lock. Time is supplied so boundary tests need no sleeping. */
final class ControlRequests {
    private static final class Pending {
        final String action; final long start; boolean waiting;
        Pending(String action,long start){this.action=action;this.start=start;}
    }
    private final Map<String,Pending> pending=new LinkedHashMap<>();
    private final Consumer<ControlStatus> sink;
    ControlRequests(Consumer<ControlStatus> sink){this.sink=sink;}
    void begin(String id,String action,long now){
        pending.put(id,new Pending(action,now));emit(id,action,ControlStatus.State.PENDING,null);
    }
    boolean isPending(String id,String action){
        Pending p=pending.get(id);return p!=null&&p.action.equals(action);
    }
    void complete(String id,String action,String error){
        Pending p=pending.get(id);if(p==null)return;
        if(action!=null&&!p.action.equals(action))throw new IllegalArgumentException("ACK_ACTION_MISMATCH");
        pending.remove(id);emit(id,p.action,error==null?ControlStatus.State.SUCCEEDED:ControlStatus.State.FAILED,error);
    }
    boolean pulse(long now){
        boolean expired=false;
        for(String id:new ArrayList<>(pending.keySet())){
            Pending p=pending.get(id);
            if(now-p.start>=5000){pending.remove(id);emit(id,p.action,ControlStatus.State.TIMED_OUT,"CONTROL_TIMEOUT");expired=true;}
            else if(now-p.start>=2000&&!p.waiting){p.waiting=true;emit(id,p.action,ControlStatus.State.WAITING,null);}
        }
        return expired;
    }
    void cancel(){
        for(String id:new ArrayList<>(pending.keySet())){
            Pending p=pending.remove(id);emit(id,p.action,ControlStatus.State.CANCELLED,"CONNECTION_CLOSED");
        }
    }
    private void emit(String id,String action,ControlStatus.State state,String error){sink.accept(new ControlStatus(id,action,state,error));}
}

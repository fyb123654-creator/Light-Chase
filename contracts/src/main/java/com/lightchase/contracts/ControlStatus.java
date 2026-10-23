package com.lightchase.contracts;
/** Local request lifecycle. Terminal states are delivered exactly once per request. */
public final class ControlStatus {
    public enum State { PENDING, WAITING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED }
    public final String requestId, action, errorCode;
    public final State state;
    public ControlStatus(String requestId, String action, State state, String errorCode) {
        this.requestId = requestId; this.action = action; this.state = state; this.errorCode = errorCode;
    }
}

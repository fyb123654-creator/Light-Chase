package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class ConnectionStatus {
    public final String state;
    public final String reason;
    public ConnectionStatus(String state, String reason) {
        this.state = state;
        this.reason = reason;
    }
}

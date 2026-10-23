package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class GatewayError {
    public final String code;
    public final String message;
    public final boolean recoverable;
    public final String requestId;
    public GatewayError(String code, String message, boolean recoverable, String requestId) {
        this.code = code;
        this.message = message;
        this.recoverable = recoverable;
        this.requestId = requestId;
    }
}

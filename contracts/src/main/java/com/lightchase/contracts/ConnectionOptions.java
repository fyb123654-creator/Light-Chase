package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class ConnectionOptions {
    public final String host;
    public final int port;
    public final String roomId;
    public final String clientBuild;
    public final String mapHash;
    public final String configHash;
    public ConnectionOptions(String host, int port, String roomId, String clientBuild, String mapHash, String configHash) {
        this.host = host;
        this.port = port;
        this.roomId = roomId;
        this.clientBuild = clientBuild;
        this.mapHash = mapHash;
        this.configHash = configHash;
    }
}

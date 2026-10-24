package com.lightchase.client;

/**
 * The {@code ConnectionStatus.state} values from {@code docs/development/02_接口契约.md}.
 *
 * <p>Kept in one place so the offline fixture and the UI cannot drift apart. The real gateway
 * (module {@code client-network}) carries its own copy; if the contract ever changes, both must be
 * updated together.
 */
final class ConnectionStates {
    static final String DISCONNECTED = "DISCONNECTED";
    static final String CONNECTING = "CONNECTING";
    static final String HANDSHAKING = "HANDSHAKING";
    static final String JOINING = "JOINING";
    static final String CONNECTED = "CONNECTED";
    static final String SUSPECT = "SUSPECT";
    static final String CLOSED = "CLOSED";

    /**
     * True while the session is still usable.
     *
     * <p>{@code SUSPECT} means "no PONG for three seconds", not "gone": the server has not dropped
     * us and the last snapshot is still valid, so the match UI must stay up. Treating it as a
     * disconnect throws the player back to the connect page mid-match.
     */
    static boolean isSessionAlive(String state) {
        return CONNECTED.equals(state) || SUSPECT.equals(state);
    }

    private ConnectionStates() { }
}

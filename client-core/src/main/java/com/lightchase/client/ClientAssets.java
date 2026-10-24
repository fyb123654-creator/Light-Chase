package com.lightchase.client;

/**
 * Where the client reads its map and rules from.
 *
 * <p>The map path lives in exactly one constant on purpose: {@code mapHash} is part of the HELLO
 * handshake, so the server, this client and the probe must move to {@code maps/v1.json} in the same
 * commit. A single constant makes a half-applied switch impossible - either the whole client moved
 * or none of it did.
 *
 * <p>Switching day is: change {@link #MAP} to {@link #FORMAL_MAP}, then update the server and the
 * probe in the same commit.
 */
final class ClientAssets {

    static final String TEST_MAP = "maps/test-room.json";
    static final String FORMAL_MAP = "maps/v1.json";
    static final String RULES = "rules/v1.json";

    /** The map this client loads. This is the switch. */
    static final String MAP = TEST_MAP;

    private ClientAssets() { }
}

package com.lightchase.client;

/**
 * Runtime access to the platform's local-network permission (Android 17 / targetSdk 37 requires
 * {@code ACCESS_LOCAL_NETWORK} to be granted before any LAN socket is opened).
 *
 * <p>Kept as an interface so {@code client-core} stays free of Android types; the Android launcher
 * implements it. A null implementation means "no platform gate", which is what the desktop tests
 * and the offline fixture use.
 */
public interface LanPermission {

    /** True when the platform already allows local-network sockets. */
    boolean granted();

    /** Ask the platform for the permission. May show a system dialog; returns immediately. */
    void request();

    /** Human-readable state for the connect page. */
    default String describe() { return granted() ? "GRANTED" : "NOT GRANTED"; }
}

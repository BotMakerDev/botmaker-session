package com.botmaker.session;

import java.time.Duration;

/**
 * How {@link Sessions#startVm} opens a game VM: which one, by the name it was set up under, and how long its
 * Windows may take to sign in when the session has to start it.
 *
 * @param name         the VM's name, as {@code VmInventory} lists it
 * @param readyTimeout how long a start may wait for the guest's sign-in and tools; a positive duration
 */
public record VmOptions(String name, Duration readyTimeout) {

    /** A cold boot of the guest to a signed-in desktop, with room to spare. */
    public static final Duration DEFAULT_READY_TIMEOUT = Duration.ofMinutes(5);

    public VmOptions {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A VM needs a name.");
        readyTimeout = readyTimeout == null || readyTimeout.isNegative() || readyTimeout.isZero()
                ? DEFAULT_READY_TIMEOUT : readyTimeout;
    }

    /** The VM named {@code name}, with {@link #DEFAULT_READY_TIMEOUT}. */
    public static VmOptions of(String name) {
        return new VmOptions(name, DEFAULT_READY_TIMEOUT);
    }
}

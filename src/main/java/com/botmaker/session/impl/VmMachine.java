package com.botmaker.session.impl;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.vm.GuestGame;
import com.botmaker.shared.vm.GuestLauncher;
import com.botmaker.shared.vm.QmpEvents;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * What a {@link VmSession} asks of its VM: the hypervisor's half, which {@link HypervisorVm} does with shared's
 * {@code VmSetup} and a test does with a stand-in.
 */
interface VmMachine {

    /** The VM's name, for a log line. */
    String name();

    /** Starts the VM without a window if it isn't running, and connects to its screen. */
    Connection start() throws IOException, InterruptedException;

    /** Whether the guest has signed in and its tools answer. */
    boolean guestReady() throws IOException, InterruptedException;

    /** Runs a Windows command line on the guest's desktop, without waiting for it. */
    void run(String command) throws IOException, InterruptedException;

    /** Whether {@code launcher} is installed in the guest. */
    boolean has(GuestLauncher launcher) throws IOException, InterruptedException;

    /**
     * Starts the guest's window list, which the screen's {@link NativeController#getAllWindows()} reads; once the
     * guest has signed in, and again after each start, as a guest restart ends it.
     */
    void listWindows() throws IOException, InterruptedException;

    /** {@code spec}'s processes in the guest, ended first with {@code stop}. */
    GuestGame.Found game(LaunchSpec spec, boolean stop) throws IOException, InterruptedException;

    /** One connection to the VM's screen. Closing it leaves the VM running. */
    interface Connection extends AutoCloseable {

        /** The screen's input and capture, through VNC. */
        NativeController controller();

        /** The one window: the guest's whole screen. */
        GenericWindow window();

        /** A copy of the screen, or {@code null} before the first frame. */
        BufferedImage capture();

        /**
         * Whether the screen is still connected. It drops when the VM's process ends, which under QEMU is also
         * how a guest restart shows: {@code -no-reboot} ends the process instead of rebooting it.
         */
        boolean alive();

        /** Whether this start booted the VM, rather than connecting to one already running. */
        boolean booted();

        /**
         * After the screen dropped: waits up to {@code wait} for the VM to end and says why it did; empty when it
         * is still running (the screen alone dropped). A reason that {@link QmpEvents.Reason#restarts()} (a
         * Windows restart, a crash) is to be started again; anything else stopped it for good.
         */
        Optional<QmpEvents.Reason> ended(Duration wait) throws InterruptedException;

        @Override
        void close();
    }
}

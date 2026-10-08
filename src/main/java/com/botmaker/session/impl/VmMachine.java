package com.botmaker.session.impl;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.vm.GuestLauncher;

import java.awt.image.BufferedImage;
import java.io.IOException;

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

        @Override
        void close();
    }
}

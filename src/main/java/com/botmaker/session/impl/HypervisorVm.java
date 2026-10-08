package com.botmaker.session.impl;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.vm.VmCredentials;
import com.botmaker.shared.vm.VmInventory;
import com.botmaker.shared.vm.VmRecord;
import com.botmaker.shared.vm.VmSetup;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * A game VM that Studio set up, driven through shared's {@code VmSetup}: VMware or QEMU, by its record. Its
 * credentials stay in this object; nothing here prints them.
 */
final class HypervisorVm implements VmMachine {

    private final VmCredentials credentials;
    /** The record as the last start left it: a QEMU start can move its ports. */
    private volatile VmRecord vm;

    private HypervisorVm(VmRecord vm, VmCredentials credentials) {
        this.vm = vm;
        this.credentials = credentials;
    }

    /**
     * The VM named {@code name}, once its Windows is installed.
     *
     * @throws IOException with a sentence for the user when there is no such VM, or it isn't set up yet
     */
    static HypervisorVm load(String name) throws IOException {
        VmRecord vm = VmInventory.find(name).orElseThrow(() -> new IOException(
                "There's no game VM named \"" + name + "\". Set one up in ⚙ Bot Settings."));
        if (vm.stage() != VmRecord.Stage.READY) {
            throw new IOException("The game VM \"" + name + "\" isn't set up yet (" + vm.stage().displayName()
                    + "). Finish its setup in ⚙ Bot Settings.");
        }
        VmCredentials credentials = VmCredentials.load(vm.folder())
                .orElseThrow(() -> new IOException("The game VM \"" + name + "\" has lost its passwords."));
        return new HypervisorVm(vm, credentials);
    }

    @Override
    public String name() {
        return vm.name();
    }

    @Override
    public Connection start() throws IOException, InterruptedException {
        boolean wasRunning = VmInventory.running(vm);
        VmSetup.Running running = VmSetup.start(vm, credentials);
        vm = running.vm();
        return new Screen(running, !wasRunning);
    }

    @Override
    public boolean guestReady() throws IOException, InterruptedException {
        return VmSetup.guestReady(vm, credentials);
    }

    @Override
    public void run(String command) throws IOException, InterruptedException {
        VmSetup.runOnDesktop(vm, credentials, command);
    }

    private record Screen(VmSetup.Running running, boolean booted) implements Connection {

        @Override
        public NativeController controller() {
            return running.screen();
        }

        @Override
        public GenericWindow window() {
            return running.screen().screen();
        }

        @Override
        public BufferedImage capture() {
            return running.screen().captureScreen();
        }

        @Override
        public boolean alive() {
            return running.screen().alive();
        }

        @Override
        public void close() {
            running.close();
        }
    }
}

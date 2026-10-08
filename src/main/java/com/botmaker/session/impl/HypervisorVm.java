package com.botmaker.session.impl;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.vm.GuestLauncher;
import com.botmaker.shared.vm.Hypervisor;
import com.botmaker.shared.vm.QmpEvents;
import com.botmaker.shared.vm.VmCredentials;
import com.botmaker.shared.vm.VmInventory;
import com.botmaker.shared.vm.VmRecord;
import com.botmaker.shared.vm.VmSetup;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

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
        return new Screen(running, !wasRunning, listen(vm));
    }

    /**
     * {@code vm}'s events, so a dropped screen can tell a Windows restart from a shutdown; {@code null} for VMware,
     * whose screen survives a restart, and for a QEMU started before VMs had an events port, or whose port another
     * listener holds.
     */
    private static QmpEvents listen(VmRecord vm) {
        if (vm.hypervisor() != Hypervisor.QEMU || vm.eventsPort() == 0) return null;
        try {
            return QmpEvents.listen(vm.eventsPort());
        } catch (IOException e) {
            Diag.log("[Session] VM " + vm.name() + ": not hearing its events (" + e.getMessage()
                    + "); a dropped screen will be read as a restart");
            return null;
        }
    }

    @Override
    public boolean guestReady() throws IOException, InterruptedException {
        return VmSetup.guestReady(vm, credentials);
    }

    @Override
    public void run(String command) throws IOException, InterruptedException {
        VmSetup.runOnDesktop(vm, credentials, command);
    }

    @Override
    public boolean has(GuestLauncher launcher) throws IOException, InterruptedException {
        return VmSetup.guestHas(vm, credentials, launcher);
    }

    /** @param events {@code null} when not heard ({@link #listen}) */
    private record Screen(VmSetup.Running running, boolean booted, QmpEvents events) implements Connection {

        /**
         * Without events, or once this connection was closed (a reconnect that failed): a VM still running dropped
         * only its screen. A stopped QEMU is read as a restart, as it was before VMs had events; a stopped VMware VM
         * restarts inside Workstation, so it was shut down.
         */
        @Override
        public Optional<QmpEvents.Reason> ended(Duration wait) throws InterruptedException {
            if (events != null && !events.closedHere()) return events.awaitEnd(wait);
            VmRecord vm = running.vm();
            if (VmInventory.running(vm)) return Optional.empty();
            return Optional.of(vm.hypervisor() == Hypervisor.QEMU ? QmpEvents.Reason.GUEST_RESET : QmpEvents.Reason.UNKNOWN);
        }

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
            if (events != null) events.close();
        }
    }
}

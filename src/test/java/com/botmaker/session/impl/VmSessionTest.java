package com.botmaker.session.impl;

import com.botmaker.session.Capability;
import com.botmaker.session.SessionHealth;
import com.botmaker.session.SessionStartException;
import com.botmaker.session.VmOptions;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.RunState;
import com.botmaker.shared.vm.GuestGame;
import com.botmaker.shared.vm.GuestLaunch;
import com.botmaker.shared.vm.GuestLauncher;
import com.botmaker.shared.vm.QmpEvents;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A VM session over a stand-in VM: start, launch, a guest restart, giving up, and a guest that never signs in. */
class VmSessionTest {

    private static final VmSession.Timing FAST = new VmSession.Timing(Duration.ofMillis(10), Duration.ZERO, Duration.ZERO);
    private static final VmOptions OPTIONS = new VmOptions("game", Duration.ofMillis(300));

    @Test
    void itWaitsForTheGuestThenShowsItsScreenAndStartsTheGameThere() throws Exception {
        FakeVm vm = new FakeVm();
        vm.readyAfter = 3;
        try (VmSession session = VmSession.start(vm, OPTIONS, FAST)) {
            assertEquals(3, vm.readyAsked, "it waited for the guest");
            assertEquals(1, vm.windowListsStarted, "the guest lists its windows once it has signed in");
            assertTrue(session.has(Capability.BACKGROUND_CLICK));
            assertEquals(new Rectangle(0, 0, 800, 600), session.screen());
            assertEquals("VM game", session.displayName());
            assertSame(vm.connections.get(0).frame, session.capture());

            session.launch(LaunchSpec.parse("steam:570"));
            assertEquals(List.of("start \"\" \"steam://rungameid/570\""), vm.commands);
            assertThrows(IllegalArgumentException.class, () -> session.launch(LaunchSpec.parse("heroic:abc")));

            session.attach(null);
            assertEquals(null, session.capture(), "detached: no window to capture");
        }
        assertTrue(vm.connections.get(0).closed, "closing disconnects");
    }

    @Test
    void theGameRunsAndStopsInTheGuestAndAGuestThatCantBeAskedIsUnknown() throws Exception {
        FakeVm vm = new FakeVm();
        LaunchSpec notepad = LaunchSpec.parse("exe:notepad.exe");
        try (VmSession session = VmSession.start(vm, OPTIONS, FAST)) {
            assertEquals(RunState.STOPPED, session.running(notepad));
            assertFalse(session.stop(notepad), "nothing to end");

            vm.processes = List.of("Notepad.exe (42)");
            assertEquals(RunState.RUNNING, session.running(notepad));
            assertTrue(session.stop(notepad));
            assertEquals(List.of(notepad, notepad), vm.stopped);
            assertEquals(RunState.STOPPED, session.running(notepad));

            vm.guestAnswers = false;
            assertEquals(RunState.UNKNOWN, session.running(notepad));
            assertFalse(session.stop(notepad));
        }
    }

    @Test
    void aGameTheLastRunLeftRunningIsntStartedTwiceAndARestartStartsItAgain() throws Exception {
        FakeVm vm = new FakeVm();
        vm.bootNext = false;
        vm.processes = List.of("game.exe (17)");
        LaunchSpec game = LaunchSpec.parse("exe:C:\\Games\\g\\game.exe");
        try (VmSession session = VmSession.start(vm, OPTIONS, FAST)) {
            session.launch(game);
            assertEquals(List.of(), vm.commands, "it already runs");

            vm.bootNext = true;
            vm.processes = List.of();
            vm.connections.get(0).alive = false; // Windows restarted the guest
            await(() -> vm.commands.size() == 1); // the game is started again after the restart
        }
    }

    @Test
    void onALinuxDisplayOfItsOwnTheGameStartsAndStartsAgainWhenTheDisplayDrops() throws Exception {
        FakeVm vm = new FakeVm();
        vm.linux = true;
        vm.bootNext = false;
        vm.processes = List.of("game (17)"); // another bot's, on another display
        LaunchSpec game = LaunchSpec.parse("exe:C:\\Games\\g\\game.exe");
        try (VmSession session = VmSession.start(vm, OPTIONS, FAST)) {
            session.launch(game);
            assertEquals(1, vm.commands.size(), "a new display runs nothing yet");

            FakeConnection first = vm.connections.get(0);
            first.why = Optional.empty(); // the VM runs on: only the display ended
            first.alive = false;
            await(() -> vm.commands.size() == 2);
            assertEquals(2, vm.connections.size(), "a display of its own again");
            assertTrue(first.closed);
        }
    }

    @Test
    void aGameWhoseLauncherTheGuestLacksIsRefusedBeforeAnythingRuns() throws Exception {
        FakeVm vm = new FakeVm();
        try (VmSession session = VmSession.start(vm, OPTIONS, FAST)) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> session.launch(LaunchSpec.parse("epic:Fortnite")));
            assertTrue(e.getMessage().startsWith("Epic Games Launcher isn't installed in the game VM game."),
                    e.getMessage());
            assertEquals(List.of(), vm.commands, "Windows would only offer to find an app for the link");

            vm.launchers = Set.of(GuestLauncher.STEAM, GuestLauncher.EPIC);
            session.launch(LaunchSpec.parse("epic:Fortnite"));
            assertEquals(1, vm.commands.size());
        }
    }

    @Test
    void whenWindowsRestartsTheGuestItStartsTheVmAgainAndTheGameWithIt() throws Exception {
        FakeVm vm = new FakeVm();
        try (VmSession session = VmSession.start(vm, OPTIONS, FAST)) {
            NativeController input = session.controller();
            session.launch(LaunchSpec.parse("exe:C:\\Games\\g.exe"));

            vm.connections.get(0).alive = false; // QEMU ended: -no-reboot
            await(() -> vm.connections.size() == 2 && session.health() == SessionHealth.HEALTHY);

            FakeConnection now = vm.connections.get(1);
            assertTrue(vm.connections.get(0).closed);
            assertEquals(List.of("start \"\" \"C:\\Games\\g.exe\"", "start \"\" \"C:\\Games\\g.exe\""), vm.commands,
                    "the restart closed the game: it is started again");
            assertSame(input, session.controller());
            assertEquals(2, vm.windowListsStarted, "the restart ended the guest's window list");
            input.mouseMove(5, 6);
            assertEquals(List.of("move 5,6"), now.input.events, "the bot's controller follows the new connection");
            assertNotSame(vm.connections.get(0).frame, session.captureScreen());
        }
    }

    @Test
    void aVmShutDownOnPurposeStaysDownAndSaysSo() throws Exception {
        FakeVm vm = new FakeVm();
        try (VmSession session = VmSession.start(vm, OPTIONS, FAST)) {
            session.launch(LaunchSpec.parse("exe:C:\\Games\\g.exe"));
            assertEquals(Optional.empty(), session.endedBecause());

            vm.connections.get(0).why = Optional.of(QmpEvents.Reason.GUEST_SHUTDOWN);
            vm.connections.get(0).alive = false;
            await(() -> session.health() == SessionHealth.DEAD);

            assertEquals(Optional.of("The game VM game was shut down (Windows shut down)."), session.endedBecause());
            assertEquals(1, vm.startsAsked, "starting it again would undo the shutdown");
            assertEquals(1, vm.commands.size());
        }
    }

    @Test
    void aCrashNobodyChoseIsStartedAgain() throws Exception {
        FakeVm vm = new FakeVm();
        try (VmSession session = VmSession.start(vm, OPTIONS, FAST)) {
            vm.connections.get(0).why = Optional.of(QmpEvents.Reason.GUEST_PANIC);
            vm.connections.get(0).alive = false;
            await(() -> vm.connections.size() == 2 && session.health() == SessionHealth.HEALTHY);
            assertEquals(Optional.empty(), session.endedBecause());
        }
    }

    @Test
    void aScreenThatDroppedWhileTheVmRunsIsConnectedAgainWithoutRelaunchingTheGame() throws Exception {
        FakeVm vm = new FakeVm();
        try (VmSession session = VmSession.start(vm, OPTIONS, FAST)) {
            session.launch(LaunchSpec.parse("exe:C:\\Games\\g.exe"));
            vm.bootNext = false;
            vm.connections.get(0).why = Optional.empty();
            vm.connections.get(0).alive = false;
            await(() -> vm.connections.size() == 2 && session.health() == SessionHealth.HEALTHY);
            assertEquals(1, vm.commands.size(), "the game never stopped");
        }
    }

    @Test
    void aVmThatKeepsDroppingIsGivenUp() throws Exception {
        FakeVm vm = new FakeVm();
        try (VmSession session = VmSession.start(vm, OPTIONS, FAST)) {
            vm.failStarts = true;
            vm.connections.get(0).alive = false;
            await(() -> session.health() == SessionHealth.DEAD);
            assertEquals(1 + VmSession.MAX_RESTARTS, vm.startsAsked);
        }
    }

    @Test
    void aGuestThatNeverSignsInFailsTheStartAndLeavesNothingOpen() {
        FakeVm vm = new FakeVm();
        vm.readyAfter = Integer.MAX_VALUE;
        SessionStartException e = assertThrows(SessionStartException.class, () -> VmSession.start(vm, OPTIONS, FAST));
        assertTrue(e.getMessage().contains("didn't sign in"), e.getMessage());
        assertTrue(vm.connections.get(0).closed);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("timed out");
            Thread.sleep(10);
        }
    }

    private static final class FakeVm implements VmMachine {
        final List<FakeConnection> connections = new CopyOnWriteArrayList<>();
        final List<String> commands = new CopyOnWriteArrayList<>();
        volatile int readyAfter = 1;
        volatile int readyAsked;
        volatile int startsAsked;
        volatile boolean failStarts;
        /** Whether the next start boots the VM, rather than finding it running. */
        volatile boolean bootNext = true;
        volatile Set<GuestLauncher> launchers = Set.of(GuestLauncher.STEAM);
        volatile int windowListsStarted;
        /** A Linux guest: each connection is a display of the bot's own, where nothing of its runs yet. */
        volatile boolean linux;
        /** The game's processes in the guest. */
        volatile List<String> processes = List.of();
        final List<LaunchSpec> stopped = new CopyOnWriteArrayList<>();
        volatile boolean guestAnswers = true;

        @Override
        public String name() {
            return "game";
        }

        @Override
        public Connection start() throws IOException {
            startsAsked++;
            if (failStarts) throw new IOException("QEMU didn't start.");
            FakeConnection c = new FakeConnection(bootNext);
            connections.add(c);
            return c;
        }

        @Override
        public boolean guestReady() {
            return ++readyAsked >= readyAfter;
        }

        @Override
        public void launch(LaunchSpec spec) {
            commands.add((linux ? GuestLaunch.linuxCommand(spec) : GuestLaunch.command(spec))
                    .orElseThrow(IllegalArgumentException::new));
        }

        @Override
        public boolean has(GuestLauncher launcher) {
            return launchers.contains(launcher);
        }

        @Override
        public Connection signedIn(Connection started) {
            windowListsStarted++;
            ((FakeConnection) started).display = linux;
            return started;
        }

        @Override
        public GuestGame.Found game(LaunchSpec spec, boolean stop) throws IOException {
            if (!guestAnswers) throw new IOException("The guest didn't answer.");
            List<String> found = processes;
            if (stop) {
                stopped.add(spec);
                processes = List.of();
            }
            return new GuestGame.Found(found.isEmpty() ? RunState.STOPPED : RunState.RUNNING, found, List.of());
        }
    }

    private static final class FakeConnection implements VmMachine.Connection {
        final BufferedImage frame = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        final RecordingInput input = new RecordingInput();
        final boolean booted;
        volatile boolean alive = true;
        volatile boolean closed;
        volatile boolean display;
        /** Why the VM ended when the screen drops: a Windows restart unless a test says otherwise. */
        volatile Optional<QmpEvents.Reason> why = Optional.of(QmpEvents.Reason.GUEST_RESET);

        FakeConnection(boolean booted) {
            this.booted = booted;
        }

        @Override
        public NativeController controller() {
            return input;
        }

        @Override
        public GenericWindow window() {
            return new GenericWindow(this, "game", new Rectangle(0, 0, 800, 600));
        }

        @Override
        public BufferedImage capture() {
            return frame;
        }

        @Override
        public boolean alive() {
            return alive && !closed;
        }

        @Override
        public boolean booted() {
            return booted;
        }

        @Override
        public boolean runsNothing() {
            return booted || display;
        }

        @Override
        public Optional<QmpEvents.Reason> ended(Duration wait) {
            return why;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RecordingInput implements NativeController {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override public GenericWindow getForegroundWindow() { return null; }
        @Override public List<GenericWindow> getChildWindows(GenericWindow parent) { return List.of(); }
        @Override public List<GenericWindow> getAllWindows() { return List.of(); }
        @Override public BufferedImage captureWindow(GenericWindow window) { return null; }
        @Override public void postLeftClick(GenericWindow window, int x, int y) { events.add("post " + x + "," + y); }
        @Override public void focusWindow(GenericWindow window) { }
        @Override public void moveWindow(GenericWindow window, int x, int y) { }
        @Override public void resizeWindow(GenericWindow window, int width, int height) { }
        @Override public void keyDown(int key) { events.add("down " + key); }
        @Override public void keyUp(int key) { events.add("up " + key); }
        @Override public void typeText(String text) { events.add("type " + text); }
        @Override public void mouseMove(int x, int y) { events.add("move " + x + "," + y); }
        @Override public void mouseButton(int button, boolean press) { events.add("button " + button + " " + press); }
        @Override public void scroll(int amount) { events.add("scroll " + amount); }
    }
}

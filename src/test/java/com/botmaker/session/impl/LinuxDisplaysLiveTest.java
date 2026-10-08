package com.botmaker.session.impl;

import com.botmaker.session.DesktopSession;
import com.botmaker.session.Sessions;
import com.botmaker.session.VmOptions;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.Rectangle;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two bots in one real Linux game VM ({@code -Dbotmaker.vm.linux.session=<name>}, set up and started or not): each
 * gets a display of its own, its game starts there, its windows are its own, a click on one display ends that
 * bot's game alone, and a stop ends the other's.
 */
@EnabledIfSystemProperty(named = "botmaker.vm.linux.session", matches = ".+")
class LinuxDisplaysLiveTest {

    @Test
    void twoBotsShareTheVmEachOnItsOwnDisplay() throws Exception {
        String name = System.getProperty("botmaker.vm.linux.session");
        LaunchSpec one = LaunchSpec.parse("cli:xmessage -center Bot one");
        LaunchSpec two = LaunchSpec.parse("cli:xmessage -geometry +40+60 Bot two");
        long started = System.nanoTime();
        try (DesktopSession first = Sessions.startVm(VmOptions.of(name));
             DesktopSession second = Sessions.startVm(VmOptions.of(name))) {
            say("two sessions open in " + (System.nanoTime() - started) / 1_000_000 + " ms: " + first.displayName());
            first.launch(one);
            second.launch(two);
            await(() -> windows(first).size() == 1 && windows(second).size() == 1);
            Rectangle a = windows(first).getFirst().getRect();
            Rectangle b = windows(second).getFirst().getRect();
            say("windows: " + a + " / " + b);
            assertNotEquals(a, b, "each display has its own window");
            assertEquals(new Rectangle(40, 60, a.width, a.height).getLocation(), b.getLocation(),
                    "the frame where the window manager put it");
            await(() -> first.running(one) == RunState.RUNNING && second.running(two) == RunState.RUNNING);

            // xmessage's "okay" button ends it: a click there on the first display alone.
            GenericWindow okay = windows(first).getFirst();
            first.controller().postLeftClick(okay, 23, okay.getRect().height - 18);
            await(() -> first.running(one) == RunState.STOPPED);
            assertEquals(RunState.RUNNING, second.running(two), "the other bot's game didn't see the click");
            assertEquals(1, windows(second).size());

            assertTrue(second.stop(two));
            await(() -> second.running(two) == RunState.STOPPED);
            say("click and stop each reached one display");
        }
    }

    private static List<GenericWindow> windows(DesktopSession session) {
        return session.controller().getAllWindows().stream()
                .filter(w -> !w.getRect().equals(session.screen()))
                .toList();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("timed out");
            Thread.sleep(250);
        }
    }

    private static void say(String line) {
        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + " " + line);
    }
}

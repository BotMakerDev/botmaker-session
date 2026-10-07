package com.botmaker.session.launch;

import com.botmaker.shared.launch.HostLauncherProbe;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The up-front "can this be confined?" decision, against injected probes — no real {@code PATH}, no process
 * table. What is worth pinning is that each cause of failure is named as <em>itself</em>: they used to be one
 * indistinguishable timeout, and they want three different actions from the user.
 */
class LaunchIsolationTest {

    private static final Predicate<LaunchSpec> NO_HOST_LAUNCHER = spec -> false;

    private static LaunchSpec spec(LaunchKind kind, String token) {
        return new LaunchSpec(kind, token);
    }

    private static Predicate<String> installed(String... programs) {
        Set<String> present = Set.of(programs);
        return present::contains;
    }

    @Test
    void aNativeBinaryOnPathIsIsolatableAndNamesTheCommandThatWouldRun() {
        LaunchIsolation.Verdict v = LaunchIsolation.check(
                spec(LaunchKind.HEROIC, "Firestone"), installed("heroic"), NO_HOST_LAUNCHER);

        assertTrue(v.isolatable());
        assertEquals(LaunchIsolation.Blocker.NONE, v.blocker());
        assertEquals("heroic", v.command().get(0));
        assertNull(v.reason());
    }

    @Test
    void aFlatpakOnlyTargetIsIsolatableExactlyWhenAPrivateBusCanOwnItsPortal() {
        // The whole point of the private bus: with dbus-daemon we can give the session its own Flatpak portal,
        // so the game's container is spawned from *our* environment and stays on :N.
        LaunchIsolation.Verdict withBus = LaunchIsolation.check(
                spec(LaunchKind.HEROIC, "Firestone"),
                installed("flatpak", LaunchIsolation.PRIVATE_BUS_BINARY), NO_HOST_LAUNCHER);
        assertTrue(withBus.isolatable());
        assertEquals(List.of("flatpak", "run", "com.heroicgameslauncher.hgl",
                "--no-gui", "--no-sandbox", "heroic://launch/Firestone"), withBus.command());

        // Without it, the host's portal — a D-Bus-activated service holding DISPLAY=:0 — would spawn the game on
        // the real desktop. Refusing up front is the difference between a sentence and a two-minute mystery.
        LaunchIsolation.Verdict withoutBus = LaunchIsolation.check(
                spec(LaunchKind.HEROIC, "Firestone"), installed("flatpak"), NO_HOST_LAUNCHER);
        assertFalse(withoutBus.isolatable());
        assertEquals(LaunchIsolation.Blocker.PORTAL_WOULD_ESCAPE, withoutBus.blocker());
        assertTrue(withoutBus.reason().contains(LaunchIsolation.PRIVATE_BUS_BINARY), withoutBus.reason());
        assertTrue(withoutBus.reason().contains(":0"), withoutBus.reason());
        assertTrue(withoutBus.command().isEmpty());
    }

    @Test
    void aNativeRungIsPreferredAndDoesNotNeedABusAtAll() {
        // Only the native rung is a plain child process — no portal in the chain, so no bus required.
        LaunchIsolation.Verdict v = LaunchIsolation.check(
                spec(LaunchKind.STEAM, "570"), installed("steam", "flatpak"), NO_HOST_LAUNCHER);
        assertTrue(v.isolatable());
        assertEquals(List.of("steam", "-applaunch", "570"), v.command());
    }

    @Test
    void nothingInstalledIsItsOwnAnswerAndNamesWhatWasTried() {
        LaunchIsolation.Verdict v = LaunchIsolation.check(
                spec(LaunchKind.FAUGUS, "battlenet"), installed(), NO_HOST_LAUNCHER);

        assertEquals(LaunchIsolation.Blocker.NOT_INSTALLED, v.blocker());
        assertTrue(v.reason().contains("faugus-launcher"), v.reason());
        assertTrue(v.reason().contains("flatpak"), v.reason());
    }

    @Test
    void kindsWithNoChildCommandAreRefusedBeforeAnythingIsProbed() {
        // Epic hands its launch to a URL opener and an emulator app runs over ADB: there is no child process to
        // hand a private DISPLAY to, so no amount of installed software changes the answer.
        for (LaunchSpec s : List.of(spec(LaunchKind.EPIC, "Fortnite"),
                spec(LaunchKind.EMULATOR_APP, "com.app@Pie64"),
                spec(LaunchKind.UNKNOWN, "whatever"))) {
            LaunchIsolation.Verdict v = LaunchIsolation.check(
                    s, installed("flatpak", LaunchIsolation.PRIVATE_BUS_BINARY), NO_HOST_LAUNCHER);
            assertEquals(LaunchIsolation.Blocker.NO_CHILD_COMMAND, v.blocker(), s.spec());
            assertNotNull(v.reason());
        }
    }

    @Test
    void anEmulatorAppIsExplainedAsOffDesktopRatherThanAsAFailedIsolation() {
        // Same blocker as Epic's — the ladder is empty either way — but not the same situation: an emulator app
        // is already off the user's desktop, so the sentence must not read as a failure or borrow Epic's URL
        // opener. This is the wording Studio surfaces when the pilot is asked to background one.
        LaunchIsolation.Verdict v = LaunchIsolation.check(
                spec(LaunchKind.EMULATOR_APP, "com.app@Pie64"), installed(), NO_HOST_LAUNCHER);

        assertEquals(LaunchIsolation.Blocker.NO_CHILD_COMMAND, v.blocker());
        assertTrue(v.reason().contains("ADB"), v.reason());
        assertFalse(v.reason().contains("Epic"), v.reason());
    }

    @Test
    void onlyAnEmulatorAppRunsOffTheDesktop() {
        // A values() sweep on purpose: a kind added later must decide this rather than inherit it. Epic is the
        // one that looks similar (no child command) and is deliberately false — its game does reach a desktop.
        for (LaunchKind kind : LaunchKind.values()) {
            assertEquals(kind == LaunchKind.EMULATOR_APP, kind.runsOffDesktop(), kind.name());
        }
    }

    @Test
    void anOpenHostLauncherOutranksEverythingElseAndReusesTheSharedWording() {
        // Installed and otherwise perfectly isolatable, but a single-instance launcher on :0 would take the
        // launch off us regardless — and the refusal must read the same here as everywhere else.
        LaunchIsolation.Verdict v = LaunchIsolation.check(
                spec(LaunchKind.HEROIC, "Firestone"), installed("heroic"), s -> true);

        assertEquals(LaunchIsolation.Blocker.HOST_LAUNCHER_OPEN, v.blocker());
        assertEquals(HostLauncherProbe.refusalMessage(LaunchKind.HEROIC), v.reason());
    }

    @Test
    void anExeTargetIsProbedAsWrittenSoAnAbsolutePathIsNotSearchedOnPath() {
        // An exe: target is normally an absolute path; the installed-probe is handed argv[0] verbatim so the
        // real implementation can check where it points rather than hunting for it on PATH.
        String path = "/opt/game/game.x86_64";
        LaunchIsolation.Verdict v = LaunchIsolation.check(
                spec(LaunchKind.EXE, path), installed(path), NO_HOST_LAUNCHER);
        assertTrue(v.isolatable());
        assertEquals(List.of(path), v.command());
    }

    @Test
    void aNullSpecIsRefusedRatherThanThrowing() {
        LaunchIsolation.Verdict v = LaunchIsolation.check(null, installed(), NO_HOST_LAUNCHER);
        assertEquals(LaunchIsolation.Blocker.NO_CHILD_COMMAND, v.blocker());
        assertNotNull(v.reason());
    }
}

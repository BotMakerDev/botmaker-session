package com.botmaker.session.display;

import com.botmaker.session.impl.NestedSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendInstallTest {

    @Test
    void fedoraInstallsBothBackendsWithDnf() {
        Map<String, String> fedora = Map.of("ID", "fedora");
        assertEquals(List.of("dnf", "install", "-y", "gamescope"),
                BackendInstall.forBackend(NestedSession.Backend.GAMESCOPE, fedora, false).orElseThrow().command());
        assertEquals(List.of("dnf", "install", "-y", "xorg-x11-server-Xephyr"),
                BackendInstall.forBackend(NestedSession.Backend.XEPHYR, fedora, false).orElseThrow().command());
    }

    @Test
    void aDerivativeIsKnownByItsIdLike() {
        Map<String, String> mint = Map.of("ID", "linuxmint", "ID_LIKE", "ubuntu debian");
        assertEquals(List.of("apt-get", "install", "-y", "xserver-xephyr"),
                BackendInstall.forBackend(NestedSession.Backend.XEPHYR, mint, false).orElseThrow().command());
        Map<String, String> endeavour = Map.of("ID", "endeavouros", "ID_LIKE", "arch");
        assertEquals("xorg-server-xephyr",
                BackendInstall.forBackend(NestedSession.Backend.XEPHYR, endeavour, false).orElseThrow()
                        .command().getLast());
    }

    @Test
    void anImageBasedSystemGetsACommandToCopyNotOneToRun() {
        BackendInstall kinoite = BackendInstall.forBackend(NestedSession.Backend.GAMESCOPE,
                Map.of("ID", "fedora", "VARIANT_ID", "kinoite"), true).orElseThrow();
        assertEquals(List.of("rpm-ostree", "install", "gamescope"), kinoite.command());
        assertTrue(kinoite.needsReboot());
        assertFalse(kinoite.runsHere(), "rpm-ostree needs a reboot, so it is never run from Studio");
        assertEquals("sudo rpm-ostree install gamescope", kinoite.describe());
    }

    @Test
    void aReadOnlySystemThatIsNotAnRpmImageHasNoCommand() {
        assertEquals(Optional.empty(), BackendInstall.forBackend(NestedSession.Backend.XEPHYR,
                Map.of("ID", "endless", "ID_LIKE", "debian"), true), "ostree, but no rpm-ostree there");
        assertEquals(Optional.empty(), BackendInstall.forBackend(NestedSession.Backend.GAMESCOPE,
                Map.of("ID", "steamos", "ID_LIKE", "arch"), false), "SteamOS's root is read-only");
    }

    @Test
    void anUnknownDistroHasNoCommand() {
        assertEquals(Optional.empty(),
                BackendInstall.forBackend(NestedSession.Backend.GAMESCOPE, Map.of("ID", "nixos"), false));
        assertEquals(Optional.empty(), BackendInstall.forBackend(NestedSession.Backend.GAMESCOPE, Map.of(), false));
    }

    @Test
    void osReleaseIsReadWithQuotesRemoved(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("os-release");
        Files.writeString(file, """
                # comment
                NAME="Fedora Linux"
                ID=fedora
                ID_LIKE='rhel centos'
                """);
        Map<String, String> values = BackendInstall.osRelease(file);
        assertEquals("Fedora Linux", values.get("NAME"));
        assertEquals("fedora", values.get("ID"));
        assertEquals("rhel centos", values.get("ID_LIKE"));
        assertEquals(Map.of(), BackendInstall.osRelease(dir.resolve("missing")));
    }
}

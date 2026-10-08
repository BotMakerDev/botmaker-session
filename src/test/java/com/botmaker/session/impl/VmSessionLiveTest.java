package com.botmaker.session.impl;

import com.botmaker.session.DesktopSession;
import com.botmaker.session.SessionHealth;
import com.botmaker.session.Sessions;
import com.botmaker.session.VmOptions;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.vm.VmCredentials;
import com.botmaker.shared.vm.VmInventory;
import com.botmaker.shared.vm.VmRecord;
import com.botmaker.shared.vm.VmSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real game VM as a session, on this computer: the VM named by {@code -Dbotmaker.vm.session}, set up by shared's
 * {@code VmSetupLiveTest}. It opens the session (starting the VM if it's off), launches Notepad on the guest's
 * desktop through the launch task, and checks the screen changed. Then it restarts the guest's Windows and waits
 * for the session to bring the VM back; the screen it comes back to is saved as {@code session-after-restart.png}
 * in the VM's folder.
 */
@EnabledIfSystemProperty(named = "botmaker.vm.session", matches = ".+")
class VmSessionLiveTest {

    @Test
    void aGameVmOpensAndStartsAProgramOnItsDesktop() throws Exception {
        long opened = System.nanoTime();
        try (DesktopSession session = Sessions.startVm(VmOptions.of(System.getProperty("botmaker.vm.session")))) {
            System.out.println("opened in " + (System.nanoTime() - opened) / 1_000_000 + " ms: " + session.screen());
            assertEquals(SessionHealth.HEALTHY, session.health());
            BufferedImage before = session.captureScreen();
            assertNotNull(before);

            long sent = System.nanoTime();
            session.launch(LaunchSpec.parse("cli:notepad.exe"));
            double change = 0;
            while (System.nanoTime() - sent < 20_000_000_000L && change < 0.02) {
                Thread.sleep(250);
                change = changed(before, session.captureScreen());
            }
            System.out.printf("Notepad changed %.1f%% of the screen after %d ms%n", change * 100,
                    (System.nanoTime() - sent) / 1_000_000);
            assertTrue(change >= 0.02, "nothing appeared on the guest's desktop");

            // Windows restarts the guest, as an update would: QEMU ends (-no-reboot) and the session brings it back,
            // with Notepad, the last launch, started again.
            VmRecord vm = VmInventory.find(System.getProperty("botmaker.vm.session")).orElseThrow();
            VmSetup.runOnDesktop(vm, VmCredentials.load(vm.folder()).orElseThrow(), "shutdown /r /t 0");
            long restarted = System.nanoTime();
            await(() -> session.health() == SessionHealth.DEGRADED, 120);
            System.out.println("screen dropped after " + (System.nanoTime() - restarted) / 1_000_000 + " ms");
            await(() -> session.health() == SessionHealth.HEALTHY, 600);
            System.out.println("back after " + (System.nanoTime() - restarted) / 1_000_000 + " ms");
            Thread.sleep(5_000);
            BufferedImage back = session.captureScreen();
            assertNotNull(back);
            ImageIO.write(back, "png", vm.folder().resolve("session-after-restart.png").toFile());
            session.launch(LaunchSpec.parse("cli:taskkill /IM notepad.exe"));
        }
    }

    private static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long until = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("not within " + seconds + " s");
            Thread.sleep(250);
        }
    }

    private static double changed(BufferedImage a, BufferedImage b) {
        if (b == null) return 0;
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return 1;
        long differ = 0;
        for (int y = 0; y < a.getHeight(); y += 2) {
            for (int x = 0; x < a.getWidth(); x += 2) {
                if ((a.getRGB(x, y) & 0xFFFFFF) != (b.getRGB(x, y) & 0xFFFFFF)) differ++;
            }
        }
        return differ / ((a.getWidth() / 2.0) * (a.getHeight() / 2.0));
    }
}

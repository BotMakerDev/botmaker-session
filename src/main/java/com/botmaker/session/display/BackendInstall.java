package com.botmaker.session.display;

import com.botmaker.session.SessionBackend;
import com.botmaker.shared.Executables;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * How to install a missing private-display backend on this machine: the package manager's command, run through
 * {@code pkexec} so the system asks for the password, or — on an image-based distro — the command to copy.
 *
 * <p>Studio's packages recommend both backends, so a fresh install has them and this is the path for a user who
 * removed one or installed Studio another way. The distro is read from {@code /etc/os-release} ({@code ID} first,
 * then {@code ID_LIKE}); one we don't know gets no command, and the caller shows
 * {@link SessionBackends#installHint} instead.
 *
 * @param command     the package manager's argv, without {@code pkexec}
 * @param needsReboot an image-based system ({@code rpm-ostree}): the user runs the command and reboots, so
 *                    {@link #runsHere()} is false
 */
public record BackendInstall(List<String> command, boolean needsReboot) {

    /** How long a package install may take before it is given up on: a slow mirror, not a hang. */
    private static final long TIMEOUT_MINUTES = 15;

    public BackendInstall {
        command = List.copyOf(command);
    }

    /** The package-manager families this knows the names for. */
    enum Family {
        FEDORA("dnf", "install", "-y"),
        DEBIAN("apt-get", "install", "-y"),
        ARCH("pacman", "-S", "--needed", "--noconfirm"),
        SUSE("zypper", "--non-interactive", "install"),
        OSTREE("rpm-ostree", "install");

        private final List<String> install;

        Family(String... install) {
            this.install = List.of(install);
        }

        /** The package that ships {@code backend}'s binary under this family's names. */
        String packageFor(SessionBackend backend) {
            return switch (backend) {
                case GAMESCOPE -> "gamescope";
                case XEPHYR -> switch (this) {
                    case FEDORA, OSTREE -> "xorg-x11-server-Xephyr";
                    case DEBIAN -> "xserver-xephyr";
                    case ARCH -> "xorg-server-xephyr";
                    case SUSE -> "xorg-x11-server-extra";
                };
            };
        }
    }

    /** The install for {@code backend} on this machine, or empty when the distro is not one this knows. */
    public static Optional<BackendInstall> forBackend(SessionBackend backend) {
        return forBackend(backend, osRelease(Path.of("/etc/os-release")),
                Files.exists(Path.of("/run/ostree-booted")));
    }

    /** The testable seam: {@code osRelease} as parsed key/values, {@code ostree} whether the system is image-based. */
    static Optional<BackendInstall> forBackend(SessionBackend backend, Map<String, String> osRelease,
                                               boolean ostree) {
        return family(osRelease, ostree).map(family -> {
            List<String> argv = new ArrayList<>(family.install);
            argv.add(family.packageFor(backend));
            return new BackendInstall(argv, family == Family.OSTREE);
        });
    }

    static Optional<Family> family(Map<String, String> osRelease, boolean ostree) {
        // SteamOS says ID_LIKE=arch but its root is read-only: pacman there fails, so it gets no command.
        if ("steamos".equalsIgnoreCase(osRelease.getOrDefault("ID", ""))) {
            return Optional.empty();
        }
        Optional<Family> family = packageFamily(osRelease);
        if (ostree) {
            // rpm-ostree only on an rpm image (Silverblue, Kinoite, Bazzite…); another ostree system has no
            // command this knows.
            return family.filter(f -> f == Family.FEDORA).map(f -> Family.OSTREE);
        }
        return family;
    }

    private static Optional<Family> packageFamily(Map<String, String> osRelease) {
        List<String> ids = new ArrayList<>();
        ids.add(osRelease.getOrDefault("ID", ""));
        ids.addAll(List.of(osRelease.getOrDefault("ID_LIKE", "").split("\\s+")));
        for (String id : ids) {
            switch (id.toLowerCase(Locale.ROOT)) {
                case "fedora", "rhel", "centos" -> { return Optional.of(Family.FEDORA); }
                case "debian", "ubuntu" -> { return Optional.of(Family.DEBIAN); }
                case "arch" -> { return Optional.of(Family.ARCH); }
                case "suse", "opensuse", "opensuse-tumbleweed", "opensuse-leap" -> { return Optional.of(Family.SUSE); }
                default -> { }
            }
        }
        return Optional.empty();
    }

    /** {@code /etc/os-release} as key/values, quotes removed; empty when it can't be read. */
    static Map<String, String> osRelease(Path file) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) continue;
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(line.substring(0, eq).trim(), value);
            }
        } catch (IOException | RuntimeException unreadable) {
            // no os-release: no known family, the caller shows the plain hint
        }
        return values;
    }

    /** The command as a user would type it, e.g. {@code sudo dnf install -y gamescope}. */
    public String describe() {
        return "sudo " + String.join(" ", command);
    }

    /** Whether {@link #run()} can do it here: not an image-based system, and {@code pkexec} is installed. */
    public boolean runsHere() {
        return !needsReboot && Executables.onPath("pkexec");
    }

    /**
     * Runs the install through {@code pkexec}, which shows the system's password prompt, and waits for it.
     * Blocks for as long as the download takes, so never on a UI thread.
     *
     * @return whether the package manager exited 0; {@code false} too when the user dismissed the prompt
     */
    public boolean run() throws IOException, InterruptedException {
        if (!runsHere()) {
            throw new IllegalStateException("can't install here — run " + describe() + " yourself");
        }
        List<String> argv = new ArrayList<>();
        argv.add("pkexec");
        argv.addAll(command);
        Process process = new ProcessBuilder(argv).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            // Not killed: the package manager runs as root, beyond this process's signals, and stopping it
            // half-way would leave its lock and database in worse shape than a slow download.
            return false;
        }
        return process.exitValue() == 0;
    }
}

package com.botmaker.session.launch;

import com.botmaker.shared.Executables;
import com.botmaker.shared.launch.HostLauncherProbe;
import com.botmaker.shared.launch.LaunchCommands;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.ProcessOrigin;
import com.botmaker.shared.launch.RunningProbe;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * <b>Can this target actually be confined to a private display?</b> — asked <em>before</em> bring-up, so a
 * launch either isolates or says precisely why it can't. Single-sourced here because the SDK's bot runtime, the
 * nested session itself and Studio's launch surfaces all need the same answer in the same words.
 *
 * <p><b>Why an up-front probe, rather than launching and seeing.</b> Every way this fails used to look
 * identical from the outside: the private display sat empty for the whole window budget (up to two minutes for
 * a store launcher), the half-booted child was then reaped — which is how an Electron launcher ends up dying
 * with a {@code SIGTRAP} coredump — and the user got one guess as the explanation. The three causes are
 * distinguishable up front and want three different actions, so they are named:
 *
 * <ul>
 *   <li>{@link Blocker#NO_CHILD_COMMAND} — the kind has no command form at all ({@code epic:} hands its launch
 *       to a URL opener, an emulator app runs over ADB). Nothing to hand a {@code DISPLAY} to.</li>
 *   <li>{@link Blocker#HOST_LAUNCHER_OPEN} — the launcher's UI is already up on {@code :0} and is
 *       single-instance, so our child forwards to it and the game maps on the real desktop.</li>
 *   <li>{@link Blocker#PORTAL_WOULD_ESCAPE} — the only installed form is the Flatpak one, and there is no
 *       {@code dbus-daemon} to give the session its own Flatpak portal. This is the one that motivated the
 *       whole phase: the host's {@code flatpak-portal} is a D-Bus-activated service holding {@code DISPLAY=:0},
 *       and it spawns the game's container from <em>its</em> environment, so the game escapes to the real
 *       desktop no matter what the launcher was handed. Measured live: {@code steam-runtime-launch-client}
 *       still on {@code :3}, then {@code pv-adverb} and {@code wineserver} on {@code :0}.</li>
 *   <li>{@link Blocker#NOT_INSTALLED} — no rung of the ladder names a program that exists here.</li>
 * </ul>
 *
 * <p><b>It never guesses in the safe direction.</b> Every probe behind it is best-effort and total (an
 * unreadable {@code PATH} or process table answers "no evidence"), and the verdict is only a refusal when
 * something was positively observed — refusing a launch that would have worked is worse than attempting one
 * that fails, because the attempt still ends in a message.
 */
public final class LaunchIsolation {

    /**
     * The program that gives a session its own D-Bus bus — and with it its own Flatpak portal. Named here rather
     * than only at the spawn site because "can we isolate a Flatpak target?" is exactly "is this installed?", and
     * the two must not drift into asking about different binaries.
     */
    public static final String PRIVATE_BUS_BINARY = "dbus-daemon";

    /** The argv[0] a Flatpak-form rung of a launch ladder starts with. */
    private static final String FLATPAK = "flatpak";

    /** What stops a target being confined to a private display — {@link #NONE} when nothing does. */
    public enum Blocker {
        /** Isolatable. */
        NONE,
        /** The kind has no command we can run as a child at all. */
        NO_CHILD_COMMAND,
        /** The launcher's own UI is already running on the host desktop and would swallow the launch. */
        HOST_LAUNCHER_OPEN,
        /** Only the Flatpak form is installed, and no private bus is available to own its portal. */
        PORTAL_WOULD_ESCAPE,
        /** Nothing that could start this target is installed. */
        NOT_INSTALLED
    }

    /**
     * The answer: whether {@code spec} can be isolated, which command would run it, and — when it can't — the
     * user-facing reason, already written as a whole sentence a caller can surface verbatim.
     *
     * @param blocker what stops it, or {@link Blocker#NONE}
     * @param command the ladder rung that would be run, or empty when blocked
     * @param reason  the user-facing explanation, or {@code null} when isolatable
     */
    public record Verdict(Blocker blocker, List<String> command, String reason) {

        /** Whether the launch can go ahead in a private display. */
        public boolean isolatable() {
            return blocker == Blocker.NONE;
        }
    }

    private LaunchIsolation() {}

    /** Whether a private session bus can be started here — i.e. whether a Flatpak target can be confined. */
    public static boolean privateBusAvailable() {
        return Executables.onPath(PRIVATE_BUS_BINARY);
    }

    /** Whether {@code spec} can be launched into a private display, and why not when it can't. */
    public static Verdict check(LaunchSpec spec) {
        return check(spec, Executables::exists, HostLauncherProbe::isRunning);
    }

    /**
     * The rungs of {@code spec}'s ladder that can actually be run on this machine, in preference order — what a
     * caller should try, rather than the whole ladder.
     *
     * <p>The difference is visible in a log: Heroic is Flatpak-only on a typical Linux box, so a session would
     * spawn the missing native {@code heroic} first, watch it exit immediately, announce that it "mapped no
     * window within 120000ms" (it had not waited at all) and only then reach the form that works. Nothing broke,
     * but the trace described a timeout that never happened — and on a slower ladder it would be a real wait.
     */
    public static List<List<String>> runnableLadder(LaunchSpec spec) {
        return runnableLadder(spec, 0, 0);
    }

    /**
     * The runnable ladder for a display of a known size — see {@link LaunchCommands#childLadder(LaunchSpec, int,
     * int)}. Only an emulator app's rung depends on it, and for that one it is not optional: the rung is the
     * compositor Android will render into.
     */
    public static List<List<String>> runnableLadder(LaunchSpec spec, int width, int height) {
        return runnableLadder(spec, width, height, Executables::exists);
    }

    /** {@link #runnableLadder(LaunchSpec, int, int)} against an injected probe. */
    static List<List<String>> runnableLadder(LaunchSpec spec, int width, int height, Predicate<String> installed) {
        return LaunchCommands.childLadder(spec, width, height).stream()
                .filter(rung -> !rung.isEmpty() && installed.test(rung.get(0)))
                .toList();
    }

    /**
     * {@link #check(LaunchSpec)} against injected probes — the testable seam, so the whole ladder→installed→
     * portal decision is asserted without a real {@code PATH} or process table.
     *
     * @param installed            whether an argv[0] names something runnable here
     * @param hostLauncherRunning  whether the spec's launcher UI is already up on the host
     */
    static Verdict check(LaunchSpec spec, Predicate<String> installed, Predicate<LaunchSpec> hostLauncherRunning) {
        List<List<String>> ladder = LaunchCommands.childLadder(spec);
        if (ladder.isEmpty()) {
            return new Verdict(Blocker.NO_CHILD_COMMAND, List.of(), noChildCommandReason(spec));
        }
        if (hostLauncherRunning.test(spec)) {
            // Still a refusal, but now a narrower one: HostLauncherProbe counts only launchers on the *host*
            // desktop. A launcher on one of our private displays used to land here too, which inverted the
            // feature — the setup that works (the launcher already up in a session) was the one refused, and the
            // bot then ran on :0. Whether a private bus also hides a genuine host instance from a single-instance
            // check is still unverified; until it is, "close it and retry" beats re-running the launch that
            // produced the coredump.
            return new Verdict(Blocker.HOST_LAUNCHER_OPEN, List.of(),
                    HostLauncherProbe.refusalMessage(spec.kind()));
        }
        for (List<String> rung : ladder) {
            String program = rung.isEmpty() ? null : rung.get(0);
            if (program == null || !installed.test(program)) {
                continue;
            }
            if (!FLATPAK.equals(program) || installed.test(PRIVATE_BUS_BINARY)) {
                return new Verdict(Blocker.NONE, rung, null);
            }
            // The Flatpak form is the only one we could run, and its portal would put the game on :0. Keep
            // walking: a later rung might be native (none is today, but the ladder's order is not our contract).
        }
        boolean anyFlatpak = ladder.stream().anyMatch(rung -> !rung.isEmpty() && FLATPAK.equals(rung.get(0))
                && installed.test(FLATPAK));
        return anyFlatpak
                ? new Verdict(Blocker.PORTAL_WOULD_ESCAPE, List.of(), portalEscapeReason(spec))
                : new Verdict(Blocker.NOT_INSTALLED, List.of(), notInstalledReason(spec, ladder));
    }

    /**
     * Why a launch that <em>was</em> allowed still mapped no window on the private display — the backstop for
     * the cases an up-front probe cannot see. It distinguishes the two outcomes rather than guessing between
     * them: a process carrying the target's own launch identity means the game is running <em>somewhere else</em>
     * (it escaped to the real desktop); nothing running means it never got that far.
     */
    public static String noWindowDiagnosis(LaunchSpec spec) {
        if (spec == null) {
            return "Nothing was launched.";
        }
        if (HostLauncherProbe.isRunning(spec)) {
            // A launcher opened *during* bring-up — the one case the up-front probe genuinely cannot catch.
            return HostLauncherProbe.refusalMessage(spec.kind());
        }
        if (RunningProbe.commandLineMentions(spec.token())) {
            return "It is running, but outside the private display — check your real desktop. Something in its "
                    + "launch chain re-spawned it through the host session (a Flatpak portal or a launcher "
                    + "daemon), which resets DISPLAY back to :0.";
        }
        return "Nothing is running under that target: it may still have been setting up (a first Proton/Wine "
                + "run can take minutes), or it failed to start — run it once on your desktop to see its error.";
    }

    /**
     * Why an empty ladder blocks isolation — in the words that fit <em>this</em> kind. A
     * {@link LaunchSpec#runsOffDesktop()} target isn't failing at anything: it already runs somewhere no
     * display of ours reaches, so the sentence explains rather than apologises. Every other empty ladder (Epic)
     * really is a target that will land on the user's desktop, and keeps the original wording.
     *
     * <p>Waydroid is neither: it <em>can</em> use a private display, so an empty ladder there means the one
     * thing that builds it is missing, and the sentence says which.
     */
    private static String noChildCommandReason(LaunchSpec spec) {
        if (spec != null && spec.kind() == LaunchKind.EMULATOR_APP && !spec.runsOffDesktop()) {
            return "Can't run " + spec.describe() + " in a private display: gamescope isn't installed, and it "
                    + "is what hosts Waydroid's Wayland UI on a display we own. Install gamescope, or launch it "
                    + "on your desktop with \"▶ Launch now\".";
        }
        if (spec != null && spec.runsOffDesktop()) {
            return spec.describe() + " doesn't run on your desktop at all — it runs inside the emulator and is "
                    + "driven over ADB, so it neither needs nor can use a private display. Start it with "
                    + "\"▶ Launch now\" instead; background mode doesn't apply to it.";
        }
        String kind = spec == null ? "that target" : spec.kind().displayName().toLowerCase(Locale.ROOT) + "s";
        return "Can't run " + kind + " in a private display: there is no command form to run as a child process, "
                + "so there is nothing to hand a private DISPLAY to (Epic hands its launch to a URL opener).";
    }

    private static String portalEscapeReason(LaunchSpec spec) {
        return "Can't run " + describe(spec) + " in a private display: only its Flatpak form is installed, and "
                + PRIVATE_BUS_BINARY + " isn't available to give the private display its own Flatpak portal — "
                + "the portal on your desktop would spawn the game on :0 instead, whatever DISPLAY the launcher "
                + "was given. Install " + PRIVATE_BUS_BINARY + " (the dbus package), or install a native build.";
    }

    private static String notInstalledReason(LaunchSpec spec, List<List<String>> ladder) {
        String tried = ladder.stream()
                .filter(rung -> !rung.isEmpty())
                .map(rung -> rung.get(0))
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("nothing");
        return "Can't run " + describe(spec) + " in a private display: none of the ways to start it are "
                + "installed here (tried: " + tried + ").";
    }

    private static String describe(LaunchSpec spec) {
        return spec == null ? "that target" : spec.describe();
    }
}

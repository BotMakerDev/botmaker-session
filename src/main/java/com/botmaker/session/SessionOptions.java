package com.botmaker.session;

import java.util.List;
import java.util.Map;

/**
 * How a private session is shaped: which {@link SessionBackend} hosts it, the display size, an optional window
 * manager to run in it, and any extra per-session environment (a private {@code HOME}/{@code XDG_RUNTIME_DIR}/
 * {@code WINEPREFIX} to stop a single-instance game escaping back to {@code :0}). The {@code DISPLAY} is always
 * set for you. For {@link SessionBackend#GAMESCOPE} the exact gamescope argv is overridable
 * ({@link #withGamescopeCommand}) so a real box can tune it — or switch to the child-launch form — without a code
 * change. Given to {@link Sessions#startPrivate}.
 */
public final class SessionOptions {
    private final SessionBackend backend;
    private final int width;
    private final int height;
    private final List<String> windowManagerCommand;
    private final Map<String, String> extraEnv;
    private final List<String> gamescopeCommand;
    private final long windowTimeoutMs;
    private final boolean privateBus;

    private SessionOptions(SessionBackend backend, int width, int height, List<String> wm,
                           Map<String, String> extraEnv, List<String> gamescopeCommand, long windowTimeoutMs,
                           boolean privateBus) {
        this.backend = backend;
        this.width = width;
        this.height = height;
        // null = "not stated, use the backend's default policy"; empty = "explicitly none".
        this.windowManagerCommand = wm == null ? null : List.copyOf(wm);
        this.extraEnv = Map.copyOf(extraEnv);
        this.gamescopeCommand = gamescopeCommand == null ? List.of() : List.copyOf(gamescopeCommand);
        this.windowTimeoutMs = Math.max(0, windowTimeoutMs);
        this.privateBus = privateBus;
    }

    /**
     * A 2D Xephyr session at {@code width}x{@code height}, no extra env, running the backend's default window
     * manager ({@code SessionBackends.windowManagerFor} — openbox when it's installed, since a bare Xephyr has no
     * EWMH and therefore no input focus to inject keys into).
     */
    public static SessionOptions xephyr(int width, int height) {
        return new SessionOptions(SessionBackend.XEPHYR, width, height, null, Map.of(), List.of(), 0, true);
    }

    /**
     * A hardware-3D gamescope session at {@code width}x{@code height}, no extra env. Always WM-less: gamescope is
     * itself the window manager for its embedded Xwayland.
     */
    public static SessionOptions gamescope(int width, int height) {
        return new SessionOptions(SessionBackend.GAMESCOPE, width, height, null, Map.of(), List.of(), 0, true);
    }

    /**
     * This session, but running {@code command} as its window manager (e.g. {@code "openbox"}) instead of the
     * backend default. Passing no arguments means <em>explicitly none</em>, which is how a caller opts out of the
     * Xephyr default.
     */
    public SessionOptions withWindowManager(String... command) {
        return new SessionOptions(backend, width, height, List.of(command), extraEnv, gamescopeCommand,
                windowTimeoutMs, privateBus);
    }

    /** This session, but with no window manager at all — the explicit opt-out of the backend default. */
    public SessionOptions withoutWindowManager() {
        return withWindowManager();
    }

    /** This session, but with {@code env} overlaid on every child's environment (in addition to DISPLAY). */
    public SessionOptions withExtraEnv(Map<String, String> env) {
        return new SessionOptions(backend, width, height, windowManagerCommand, env, gamescopeCommand,
                windowTimeoutMs, privateBus);
    }

    /**
     * This session, but waiting {@code millis} for the launched target's window instead of the per-kind default.
     * Zero or negative restores the default. The knob exists because "how long can this game take to draw the
     * first time" is a property of the user's machine — a cold Proton prefix on a slow disk — not something the
     * session can know.
     */
    public SessionOptions withWindowTimeout(long millis) {
        return new SessionOptions(backend, width, height, windowManagerCommand, extraEnv, gamescopeCommand, millis,
                privateBus);
    }

    /**
     * This session, but launching gamescope with {@code command} instead of the default argv. Only meaningful for
     * {@link SessionBackend#GAMESCOPE}; lets a real box adjust flags (backend, HDR, {@code --} child form) without
     * touching the display code.
     */
    public SessionOptions withGamescopeCommand(String... command) {
        return new SessionOptions(backend, width, height, windowManagerCommand, extraEnv, List.of(command),
                windowTimeoutMs, privateBus);
    }

    public SessionBackend backend() { return backend; }
    public int width() { return width; }
    public int height() { return height; }

    /** The <em>explicit</em> window-manager argv, or empty when none was stated (or none was wanted). */
    public List<String> windowManagerCommand() {
        return windowManagerCommand == null ? List.of() : windowManagerCommand;
    }

    /**
     * Whether a caller stated a window manager (including {@link #withoutWindowManager()}'s "none").
     *
     * <p>Public because it is the only thing that distinguishes "none, deliberately" from "nothing said, use the
     * backend default" — {@link #windowManagerCommand()} answers an empty list for both. That difference decides
     * whether a Xephyr display runs openbox, which for an emulator app is the difference between gamescope's
     * window covering the screen and being framed and resized by a window manager.
     */
    public boolean hasExplicitWindowManager() {
        return windowManagerCommand != null;
    }

    public Map<String, String> extraEnv() { return extraEnv; }

    /** The explicit window-wait budget in ms, or {@code 0} to use the per-kind default. */
    public long windowTimeoutMs() { return windowTimeoutMs; }

    /** Whether this session brings up its own D-Bus bus and Flatpak portal — see {@code SessionBus}. */
    public boolean privateBus() { return privateBus; }

    /**
     * This session, but sharing the host's D-Bus session bus instead of owning one. The opt-out exists to be
     * bisected with, not used: without a private bus a Flatpak launcher's game is spawned by the <em>host's</em>
     * Flatpak portal and lands on {@code :0}, and a launcher already running on the desktop will swallow the
     * launch. Display isolation still holds for anything that stays in our process tree.
     */
    public SessionOptions withoutPrivateBus() {
        return new SessionOptions(backend, width, height, windowManagerCommand, extraEnv, gamescopeCommand,
                windowTimeoutMs, false);
    }

    /** The gamescope argv {@link #withGamescopeCommand} set, or empty for the display's own default. */
    public List<String> gamescopeCommand() {
        return gamescopeCommand;
    }
}

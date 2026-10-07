package com.botmaker.session;

import com.botmaker.shared.Executables;

import java.util.Locale;
import java.util.Optional;

/**
 * Which display server hosts a private session — the 2D vs. hardware-3D choice. Part of the contract: a bot's
 * settings pin one ({@code BotSettings.DisplayBackend}), a run property names one, and every bring-up is shaped
 * by one ({@link SessionOptions}).
 */
public enum SessionBackend {
    /** Xephyr: cheap 2D host, software-rendered here. */
    XEPHYR(Executables.XEPHYR),
    /** gamescope: embedded Xwayland on the real GPU — for Proton/DXVK/Vulkan 3D targets. */
    GAMESCOPE(Executables.GAMESCOPE);

    private final String binaryName;

    SessionBackend(String binaryName) {
        this.binaryName = binaryName;
    }

    /**
     * The executable this backend spawns to host the nested display ({@code Xephyr} / {@code gamescope}).
     * Single-sourced here so a consumer probing {@code PATH} for availability can't drift from what the two
     * display classes actually run.
     */
    public String binaryName() {
        return binaryName;
    }

    /**
     * The stable lowercase wire id ({@code "xephyr"} / {@code "gamescope"}) — what the run property
     * {@code botmaker.session.backend} holds and what a generated bot passes to {@code Session.useBackend}. Kept
     * distinct from {@link #binaryName()} on purpose: that one is capitalised {@code Xephyr} because it is the
     * executable's actual name, and persisting a value that has to match an executable's spelling is how a
     * rename breaks stored configs.
     */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a backend {@link #id()} — total, and empty for anything that isn't one, which includes {@code null},
     * blank and the explicit {@code "auto"}. Empty therefore means <em>"no override, use the default"</em>
     * ({@code SessionBackends.preferredBackend}, which is gamescope), never a silent fallback to a particular
     * backend: mapping an unrecognised value onto Xephyr is exactly the software-GL crash that default exists to
     * avoid. {@link #XEPHYR} is reachable only by naming it explicitly.
     */
    public static Optional<SessionBackend> fromId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (SessionBackend backend : values()) {
            if (backend.id().equals(normalized)) {
                return Optional.of(backend);
            }
        }
        return Optional.empty();
    }
}

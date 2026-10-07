package com.botmaker.session.display;

import com.botmaker.shared.capture.GenericWindow;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recognising a compositor's output window, so the capture picker offers the thing inside it once instead of
 * twice. The failure this prevents was user-visible: a "Waydroid" tile with a black ADB thumbnail sitting next
 * to a "gamescope" tile showing the same Android, with nothing to say they were one device.
 */
class GamescopeHostTest {

    private static GenericWindow window(String title) {
        return new GenericWindow(1L, title, new Rectangle(0, 0, 1280, 661));
    }

    @Test
    void gamescopesOwnWindowIsAHost() {
        assertTrue(GamescopeHost.isHost(window("gamescope")));
        assertTrue(GamescopeHost.isHost(window("Gamescope")), "the title is matched case-insensitively");
        assertTrue(GamescopeHost.isHost(window("  gamescope  ")));
    }

    /**
     * Exact match, never {@code contains} — an editor holding this very file, or a terminal running the
     * command, is an ordinary window the user may legitimately want to capture.
     */
    @Test
    void awindowMerelyMentioningItIsNot() {
        assertFalse(GamescopeHost.isHost(window("gamescope — GamescopeHost.java")));
        assertFalse(GamescopeHost.isHost(window("run gamescope")));
        assertFalse(GamescopeHost.isHost(window("Firestone")));
        assertFalse(GamescopeHost.isHost(window(null)));
        assertFalse(GamescopeHost.isHost(null));
    }

    @Test
    void firstInPicksTheHostOutOfAWindowList() {
        GenericWindow host = window("gamescope");
        assertSame(host, GamescopeHost.firstIn(List.of(window("Firefox"), host, window("Studio"))));
        assertNull(GamescopeHost.firstIn(List.of(window("Firefox"), window("Studio"))));
        assertNull(GamescopeHost.firstIn(null));
    }
}

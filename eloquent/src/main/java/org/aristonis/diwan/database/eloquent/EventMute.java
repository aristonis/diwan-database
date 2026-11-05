package org.aristonis.diwan.database.eloquent;

/**
 * The per-thread switch behind {@code withoutEvents}. It counts nesting, so an inner call does not
 * unmute an outer one, and it removes its {@code ThreadLocal} at depth 0 so a pooled thread keeps
 * nothing. The mute does not cross to another thread.
 */
final class EventMute {

    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<>();

    private EventMute() {
    }

    /** Mutes events on the current thread until the matching {@code exit()}. */
    static void enter() {
        int[] depth = DEPTH.get();
        if (depth == null) {
            depth = new int[1];
            DEPTH.set(depth);
        }
        depth[0]++;
    }

    /** Ends one {@code enter()}; at depth 0 the thread-local is removed. */
    static void exit() {
        int[] depth = DEPTH.get();
        if (depth == null) {
            return;
        }
        if (--depth[0] <= 0) {
            DEPTH.remove();
        }
    }

    /**
     * Returns whether events are muted on the current thread. It creates no thread-local entry.
     *
     * @return {@code true} between {@code enter()} and the last {@code exit()}
     */
    static boolean isMuted() {
        return DEPTH.get() != null;
    }
}

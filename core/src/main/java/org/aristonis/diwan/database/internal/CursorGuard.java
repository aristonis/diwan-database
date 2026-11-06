package org.aristonis.diwan.database.internal;

/**
 * Keeps the one cursor that may be open on a borrowed session, shared by every connection that
 * works on that session: the session callback's connection, and each transaction level and scope
 * opened on it. While a cursor is open, every other statement and transaction command on the
 * session is refused, because a JDBC driver may close or invalidate the open result set when the
 * connection runs something else. Confined to the thread that owns the session.
 */
final class CursorGuard {

    static final String OPEN_MESSAGE = "A cursor is open on this connection; close it first";

    private volatile TranslatingCursor open;
    // The transaction level (0 for a plain session) whose end closes the open cursor.
    private volatile int level;

    /** Fails when a cursor is open on the session. */
    void check() {
        if (open != null) {
            throw new IllegalStateException(OPEN_MESSAGE);
        }
    }

    /** Records the cursor just opened at the given level. */
    void register(TranslatingCursor cursor, int level) {
        check();
        open = cursor;
        this.level = level;
    }

    /** Forgets the open cursor; run by the cursor itself once it is closed. */
    void release() {
        open = null;
    }

    /** Closes the open cursor when it was opened at {@code fromLevel} or deeper. */
    void closeFrom(int fromLevel) {
        TranslatingCursor cursor = open;
        if (cursor != null && level >= fromLevel) {
            cursor.close();
        }
    }

    /** Closes as {@link #closeFrom(int)} does, returning a failure instead of throwing it. */
    Throwable closeQuietlyFrom(int fromLevel) {
        try {
            closeFrom(fromLevel);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }
}

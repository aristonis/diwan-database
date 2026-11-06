package org.aristonis.diwan.database;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.aristonis.diwan.database.driver.DriverException;
import org.aristonis.diwan.database.driver.RowCursor;

/**
 * Wraps a driver cursor for callers: rows become unmodifiable copies, a driver failure closes the
 * cursor and is thrown as the connection's {@link QueryException}, and closing hands the session
 * back to whoever lent it. Confined to the thread that opened it.
 */
final class TranslatingCursor implements RowCursor {

    private static final Logger LOGGER = Logger.getLogger(TranslatingCursor.class.getName());

    private final RowCursor delegate;
    private final Function<DriverException, RuntimeException> translate;
    // Receives null after a clean close, or the failure that closed the cursor.
    private final Consumer<Throwable> release;
    private boolean isIterated;
    private volatile boolean isClosed;
    // Won by exactly one closer, so the session is handed back once even when close() races a failure.
    private final AtomicBoolean isHandedBack = new AtomicBoolean();
    // Set when the last row was read, so next() reports the end rather than a closed cursor.
    private volatile boolean isConsumed;

    TranslatingCursor(String connectionName, RowCursor delegate, Function<DriverException, RuntimeException> translate,
            Consumer<Throwable> release) {
        this.delegate = delegate;
        this.translate = translate;
        this.release = release;
        // A cursor nobody closes keeps its connection; the creation site is what finds the leak.
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Opened a cursor on connection '" + connectionName + "'",
                    new Throwable("cursor opened here"));
        }
    }

    @Override
    public Iterator<Map<String, Object>> iterator() {
        if (isIterated) {
            throw new IllegalStateException("A cursor can be iterated only once");
        }
        isIterated = true;
        return this;
    }

    @Override
    public boolean hasNext() {
        if (isClosed) {
            return false;
        }
        boolean hasRow;
        try {
            hasRow = delegate.hasNext();
        } catch (IllegalStateException refusal) {
            throw refusal;
        } catch (DriverException failure) {
            throw closeAfterFailure(translate.apply(failure));
        } catch (RuntimeException failure) {
            throw closeAfterFailure(failure);
        } catch (Error failure) {
            throw closeAfterFailure(failure);
        }
        if (!hasRow) {
            isConsumed = true;
            close();
        }
        return hasRow;
    }

    @Override
    public Map<String, Object> next() {
        if (isClosed && !isConsumed) {
            throw new IllegalStateException("The cursor is closed");
        }
        if (!hasNext()) {
            throw new NoSuchElementException("The cursor has no more rows");
        }
        try {
            // Copied because the driver contract lets drivers return mutable maps.
            return Collections.unmodifiableMap(new LinkedHashMap<>(delegate.next()));
        } catch (IllegalStateException refusal) {
            throw refusal;
        } catch (DriverException failure) {
            throw closeAfterFailure(translate.apply(failure));
        } catch (RuntimeException failure) {
            throw closeAfterFailure(failure);
        } catch (Error failure) {
            throw closeAfterFailure(failure);
        }
    }

    // The statement is released before the session, and the session even when the statement fails.
    // An IllegalStateException is the driver refusing the call (Android's cursor refuses any thread
    // but its owner), not a failed close: the cursor stays open so the owner can still close it, and
    // the session is not handed back from a thread that does not own it.
    @Override
    public void close() {
        if (isClosed) {
            return;
        }
        Throwable failure = null;
        try {
            delegate.close();
        } catch (IllegalStateException refusal) {
            throw refusal;
        } catch (DriverException closeFailure) {
            failure = translate.apply(closeFailure);
        } catch (RuntimeException | Error closeFailure) {
            failure = closeFailure;
        }
        isClosed = true;
        if (isHandedBack.compareAndSet(false, true)) {
            failure = handBack(failure);
        }
        if (failure != null) {
            Sessions.rethrow(failure);
        }
    }

    @Override
    public boolean isClosed() {
        return isClosed;
    }

    private <T extends Throwable> T closeAfterFailure(T failure) {
        if (isClosed) {
            return failure;
        }
        isClosed = true;
        try {
            delegate.close();
        } catch (Throwable closeFailure) {
            Sessions.suppress(failure, closeFailure);
        } finally {
            if (isHandedBack.compareAndSet(false, true)) {
                handBack(failure);
            }
        }
        return failure;
    }

    private Throwable handBack(Throwable failure) {
        try {
            release.accept(failure);
            return failure;
        } catch (Throwable releaseFailure) {
            if (failure == null) {
                return releaseFailure;
            }
            Sessions.suppress(failure, releaseFailure);
            return failure;
        }
    }
}

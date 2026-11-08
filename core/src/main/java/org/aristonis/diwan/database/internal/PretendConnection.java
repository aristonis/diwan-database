package org.aristonis.diwan.database.internal;

import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.PretendCallback;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.TransactionOptions;
import org.aristonis.diwan.database.TransactionScope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.aristonis.diwan.database.driver.RowCursor;

/**
 * Records every statement as a {@link LoggedQuery} instead of sending it, and opens no session.
 * Confined to the thread that runs the pretend callback; the recorder and its scopes refuse any use
 * once the callback has returned.
 */
class PretendConnection extends AbstractConnection {

    private static final String ENDED_MESSAGE = "This pretend recorder already ended; it may only be used"
            + " inside its pretend callback";

    private final List<LoggedQuery> queries;
    private final AtomicBoolean recorderEnded;

    PretendConnection(ConnectionSettings settings, List<LoggedQuery> queries, AtomicBoolean recorderEnded) {
        super(settings);
        this.queries = queries;
        this.recorderEnded = recorderEnded;
    }

    /** Runs {@code callback} on a new recorder and returns a snapshot of what it recorded. */
    static List<LoggedQuery> record(ConnectionSettings settings, PretendCallback callback) {
        Objects.requireNonNull(callback, "callback");
        List<LoggedQuery> queries = new ArrayList<>();
        AtomicBoolean ended = new AtomicBoolean();
        try {
            callback.run(new PretendConnection(settings, queries, ended));
        } finally {
            ended.set(true);
        }
        return Collections.unmodifiableList(new ArrayList<>(queries));
    }

    // Nothing runs in pretend mode, so every event reports zero time.
    @Override
    final Duration elapsedSince(long startNanos) {
        return Duration.ZERO;
    }

    void ensureOpen() {
        if (recorderEnded.get()) {
            throw new IllegalStateException(ENDED_MESSAGE);
        }
    }

    @Override
    final <T> T run(PendingStatement<T> statement) {
        ensureOpen();
        queries.add(new LoggedQuery(statement.sql(), statement.bindings()));
        return statement.pretendResult();
    }

    // A cursor needs rows to stream, and a recorder has none to give.
    @Override
    final RowCursor openCursor(String sql, List<Object> bindings) {
        ensureOpen();
        throw new UnsupportedOperationException("A cursor is not available in pretend mode");
    }

    @Override
    public final <T> T transaction(TransactionCallback<T> callback) {
        return transaction(callback, 1);
    }

    // Nothing runs, so there is no lock to wait for: the options are checked and the lock wait is
    // ignored, and the callback runs once whatever the attempts.
    @Override
    public final <T> T transaction(TransactionCallback<T> callback, TransactionOptions options) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(options, "options");
        ensureOpen();
        return callback.run(this);
    }

    @Override
    public final TransactionScope beginTransaction() {
        ensureOpen();
        return new Scope(settings(), queries, recorderEnded);
    }

    @Override
    public final List<LoggedQuery> pretend(PretendCallback callback) {
        ensureOpen();
        return record(settings(), callback);
    }

    // Nothing runs, so there is no lock to wait for: the limit is ignored.
    @Override
    public final <T> T withStatementLockWait(long millis, Supplier<T> statement) {
        ensureOpen();
        return statement.get();
    }

    @Override
    public final int transactionLevel() {
        return 0;
    }

    /** A scope over the same recorder; ending it only stops further recording through it. */
    private static final class Scope extends PretendConnection implements TransactionScope {

        private volatile boolean ended;

        Scope(ConnectionSettings settings, List<LoggedQuery> queries, AtomicBoolean recorderEnded) {
            super(settings, queries, recorderEnded);
        }

        @Override
        void ensureOpen() {
            if (ended) {
                throw new IllegalStateException("This transaction scope already ended");
            }
            super.ensureOpen();
        }

        @Override
        public void commit() {
            ensureOpen();
            ended = true;
        }

        @Override
        public void close() {
            ended = true;
        }
    }
}

package org.aristonis.diwan.database.event;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.ConnectionBuilder;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.exception.ListenerFailedException;
import org.aristonis.diwan.database.internal.QueryLog;
import org.aristonis.diwan.database.event.TransactionPhase;
import org.aristonis.diwan.database.internal.TransactionState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Registers listeners, keeps the query log and runs the slow-query handlers of one connection family.
 *
 * <p>A root connection and every session, transaction and scope derived from it share one
 * instance, returned by {@link Connection#events()}. Registration is safe from any thread, even
 * while statements run.
 *
 * <p>While a listener or handler runs, statements the same thread runs on the connection fire no
 * events and are not logged, which rules out recursion and a log that grows by reading itself.
 *
 * <p>When nothing listens, no handler is set and the log is off, a statement costs nothing extra:
 * the connection reads no clock and builds no event.
 *
 * <p>Instances are safe to share between threads.
 */
public final class ConnectionEvents {

    /** The query log size used when the builder sets none. */
    @Internal
    public static final int DEFAULT_QUERY_LOG_LIMIT = 1000;

    private static final String LISTENER_FAILED =
            "A connection listener failed after the statement had already succeeded";

    /** Serializes registration and log switches, so {@link #active} is always recomputed from a stable view. */
    private final Object stateLock = new Object();
    private final AtomicReference<List<ConnectionListener>> listeners =
            new AtomicReference<List<ConnectionListener>>(Collections.<ConnectionListener>emptyList());
    private final AtomicReference<List<PerQueryHandler>> perQueryHandlers =
            new AtomicReference<List<PerQueryHandler>>(Collections.<PerQueryHandler>emptyList());
    private final AtomicReference<List<TotalTimeHandler>> totalTimeHandlers =
            new AtomicReference<List<TotalTimeHandler>>(Collections.<TotalTimeHandler>emptyList());
    private final QueryLog queryLog;
    private final LongAdder totalNanos = new LongAdder();
    private final ThreadLocal<int[]> dispatchDepth = new ThreadLocal<int[]>();
    private volatile boolean isLogging;
    private volatile boolean isActive;

    /**
     * Creates the events of one family with a log bounded to {@code queryLogLimit} entries.
     *
     * @param queryLogLimit the most entries the query log keeps, at least 1
     * @param initialListeners the listeners registered from the start, never {@code null}
     */
    @Internal
    public ConnectionEvents(int queryLogLimit, List<ConnectionListener> initialListeners) {
        this(queryLogLimit, initialListeners, true);
    }

    /**
     * Creates the events of one family; the log keeps binding values only when {@code isKeepingBindings}.
     *
     * @param queryLogLimit the most entries the query log keeps, at least 1
     * @param initialListeners the listeners registered from the start, never {@code null}
     * @param isKeepingBindings whether the log keeps binding values
     * @throws IllegalArgumentException if {@code queryLogLimit} is less than 1
     */
    @Internal
    public ConnectionEvents(int queryLogLimit, List<ConnectionListener> initialListeners, boolean isKeepingBindings) {
        if (queryLogLimit < 1) {
            throw new IllegalArgumentException("queryLogLimit must be greater than 0");
        }
        this.queryLog = new QueryLog(queryLogLimit, isKeepingBindings);
        List<ConnectionListener> copy = new ArrayList<ConnectionListener>(initialListeners);
        for (ConnectionListener listener : copy) {
            Objects.requireNonNull(listener, "listener");
        }
        listeners.set(Collections.unmodifiableList(copy));
        refreshActive();
    }

    /**
     * Adds a listener that receives every query and transaction event.
     *
     * @param listener the listener, never {@code null}
     * @return a handle that removes the listener, never {@code null}
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public Registration addListener(ConnectionListener listener) {
        Objects.requireNonNull(listener, "listener");
        return register(listeners, listener);
    }

    /**
     * Adds a function that receives every {@link QueryExecuted} event.
     *
     * @param consumer the function, never {@code null}
     * @return a handle that removes it, never {@code null}
     * @throws NullPointerException if {@code consumer} is {@code null}
     */
    public Registration listen(Consumer<QueryExecuted> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        return addListener(new ConnectionListener() {
            @Override
            public void queryExecuted(QueryExecuted event) {
                consumer.accept(event);
            }
        });
    }

    /** Starts recording statements in the query log; entries already recorded are kept. */
    public void enableQueryLog() {
        synchronized (stateLock) {
            isLogging = true;
            refreshActive();
        }
    }

    /** Stops recording statements; entries already recorded are kept. */
    public void disableQueryLog() {
        synchronized (stateLock) {
            isLogging = false;
            refreshActive();
        }
    }

    /**
     * Tells whether statements are being recorded.
     *
     * @return {@code true} while the query log is on
     */
    public boolean isLoggingQueries() {
        return isLogging;
    }

    /**
     * Returns a copy of the query log, oldest entry first.
     *
     * @apiNote The log keeps at most the limit set with {@link ConnectionBuilder#queryLogLimit(int)};
     *     the oldest entries are dropped first. Binding values are stored compactly: a {@code byte[]}
     *     appears as {@code <byte[n]>} and a string over 1024 characters is cut and ends with
     *     {@code ...(n chars)}. When the connection was built with
     *     {@code showBindingsInErrors(false)}, no binding value is kept: each one appears as
     *     {@code ?}.
     * @return an unmodifiable list, never {@code null}
     */
    public List<LoggedQuery> getQueryLog() {
        return queryLog.snapshot();
    }

    /** Empties the query log. */
    public void flushQueryLog() {
        queryLog.clear();
    }

    /**
     * Calls a handler for each statement that took longer than a threshold.
     *
     * @param threshold the limit, never {@code null} and not negative; a statement must take
     *     strictly longer
     * @param handler the handler, never {@code null}; it runs on the thread that ran the statement
     * @return a handle that removes the handler, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code threshold} is negative
     */
    public Registration whenQueryTakesLongerThan(Duration threshold, Consumer<QueryExecuted> handler) {
        long nanos = toNanos(threshold);
        Objects.requireNonNull(handler, "handler");
        PerQueryHandler entry = new PerQueryHandler(nanos, handler);
        return register(perQueryHandlers, entry);
    }

    /**
     * Calls a handler once when the statements of this family have taken longer than a threshold in total.
     *
     * @apiNote The handler runs for the statement that pushes the total past the threshold and then
     *     stays quiet until {@link #resetTotalQueryDuration()}. It receives the connection that ran
     *     that statement.
     * @param threshold the limit, never {@code null} and not negative
     * @param handler the handler, never {@code null}; it runs on the thread that ran the statement
     * @return a handle that removes the handler, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code threshold} is negative
     */
    public Registration whenQueryingForLongerThan(Duration threshold, BiConsumer<Connection, QueryExecuted> handler) {
        long nanos = toNanos(threshold);
        Objects.requireNonNull(handler, "handler");
        TotalTimeHandler entry = new TotalTimeHandler(nanos, handler);
        return register(totalTimeHandlers, entry);
    }

    /**
     * Returns the time the statements of this family have taken since the last reset.
     *
     * @apiNote Only statements run while something listens, logs or has a handler are counted.
     * @return the total, never {@code null}
     */
    public Duration totalQueryDuration() {
        return Duration.ofNanos(totalNanos.sum());
    }

    /** Sets the total query duration to zero and lets the total-time handlers fire again. */
    public void resetTotalQueryDuration() {
        totalNanos.reset();
        for (TotalTimeHandler handler : totalTimeHandlers.get()) {
            handler.hasRun.set(false);
        }
    }

    /**
     * Tells whether anything needs an event: a listener, a handler or the log.
     *
     * @return {@code true} if a listener, a handler or the log is active
     */
    @Internal
    public boolean isActive() {
        return isActive;
    }

    /**
     * Tells whether at least one {@link ConnectionListener} is registered.
     *
     * @return {@code true} if a listener is registered
     */
    @Internal
    public boolean hasListeners() {
        return !listeners.get().isEmpty();
    }

    /**
     * Tells whether the calling thread is inside a listener or handler.
     *
     * @return {@code true} if the calling thread is inside a listener or handler
     */
    @Internal
    public boolean isDispatching() {
        int[] depth = dispatchDepth.get();
        return depth != null && depth[0] > 0;
    }

    /**
     * Records a successful statement and tells the listeners and handlers about it.
     *
     * <p>Every listener and handler runs. If any failed, the first failure is thrown after all ran,
     * wrapped in a {@link ListenerFailedException}, with the others suppressed onto it. An
     * {@link Error} from a listener does not skip the others; the first failure being an
     * {@code Error}, it is thrown as it is, with the others suppressed onto it.
     *
     * @param source the connection the statement ran on, never {@code null}
     * @param event the finished statement, never {@code null}
     */
    @Internal
    public void executed(Connection source, QueryExecuted event) {
        if (!isActive || isDispatching()) {
            return;
        }
        Throwable failure = null;
        enterDispatch();
        try {
            if (isLogging) {
                queryLog.record(event);
            }
            totalNanos.add(toNanos(event.time()));
            for (ConnectionListener listener : listeners.get()) {
                try {
                    listener.queryExecuted(event);
                } catch (Throwable e) {
                    failure = TransactionState.combine(failure, e);
                }
            }
            failure = runSlowHandlers(source, event, failure);
        } finally {
            exitDispatch();
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure != null) {
            throw new ListenerFailedException(LISTENER_FAILED, failure);
        }
    }

    /**
     * Tells the listeners a statement failed. Any {@link Throwable} a listener throws is suppressed
     * onto the event's exception, and the remaining listeners still run.
     *
     * @param event the failed statement, never {@code null}
     */
    @Internal
    public void failed(QueryFailed event) {
        List<ConnectionListener> snapshot = listeners.get();
        if (snapshot.isEmpty() || isDispatching()) {
            return;
        }
        enterDispatch();
        try {
            for (ConnectionListener listener : snapshot) {
                try {
                    listener.queryFailed(event);
                } catch (Throwable e) {
                    if (e != event.exception()) {
                        event.exception().addSuppressed(e);
                    }
                }
            }
        } finally {
            exitDispatch();
        }
    }

    /**
     * Tells the listeners about a transaction step.
     *
     * <p>A failure during {@link TransactionPhase#COMMITTING} propagates at once. In every other
     * phase all listeners run, then the first failure is thrown with the others suppressed onto it.
     *
     * @param phase the transaction step, never {@code null}
     * @param event the transaction, never {@code null}
     */
    @Internal
    public void transaction(TransactionPhase phase, TransactionEvent event) {
        List<ConnectionListener> snapshot = listeners.get();
        if (snapshot.isEmpty() || isDispatching()) {
            return;
        }
        RuntimeException failure = null;
        enterDispatch();
        try {
            for (ConnectionListener listener : snapshot) {
                try {
                    dispatch(listener, phase, event);
                } catch (RuntimeException e) {
                    if (phase == TransactionPhase.COMMITTING) {
                        throw e;
                    }
                    failure = collect(failure, e);
                }
            }
        } finally {
            exitDispatch();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Throwable runSlowHandlers(Connection source, QueryExecuted event, Throwable failure) {
        Throwable result = failure;
        long timeNanos = toNanos(event.time());
        for (PerQueryHandler handler : perQueryHandlers.get()) {
            if (timeNanos > handler.thresholdNanos) {
                try {
                    handler.handler.accept(event);
                } catch (Throwable e) {
                    result = TransactionState.combine(result, e);
                }
            }
        }
        long total = totalNanos.sum();
        for (TotalTimeHandler handler : totalTimeHandlers.get()) {
            if (total > handler.thresholdNanos && handler.hasRun.compareAndSet(false, true)) {
                try {
                    handler.handler.accept(source, event);
                } catch (Throwable e) {
                    result = TransactionState.combine(result, e);
                }
            }
        }
        return result;
    }

    private static void dispatch(ConnectionListener listener, TransactionPhase phase, TransactionEvent event) {
        switch (phase) {
            case BEGINNING:
                listener.transactionBeginning(event);
                break;
            case COMMITTING:
                listener.transactionCommitting(event);
                break;
            case COMMITTED:
                listener.transactionCommitted(event);
                break;
            case ROLLED_BACK:
                listener.transactionRolledBack(event);
                break;
            default:
                throw new IllegalStateException("Unknown phase " + phase);
        }
    }

    private static RuntimeException collect(RuntimeException first, RuntimeException next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private void enterDispatch() {
        int[] depth = dispatchDepth.get();
        if (depth == null) {
            depth = new int[1];
            dispatchDepth.set(depth);
        }
        depth[0]++;
    }

    private void exitDispatch() {
        int[] depth = dispatchDepth.get();
        if (depth != null && --depth[0] <= 0) {
            dispatchDepth.remove();
        }
    }

    private <T> Registration register(AtomicReference<List<T>> reference, T item) {
        synchronized (stateLock) {
            reference.set(append(reference.get(), item));
            refreshActive();
        }
        return new OnceRegistration(() -> {
            synchronized (stateLock) {
                reference.set(without(reference.get(), item));
                refreshActive();
            }
        });
    }

    private void refreshActive() {
        isActive = isLogging || !listeners.get().isEmpty() || !perQueryHandlers.get().isEmpty()
                || !totalTimeHandlers.get().isEmpty();
    }

    private static long toNanos(Duration duration) {
        Objects.requireNonNull(duration, "threshold");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
    }

    private static <T> List<T> append(List<T> list, T item) {
        List<T> copy = new ArrayList<T>(list.size() + 1);
        copy.addAll(list);
        copy.add(item);
        return Collections.unmodifiableList(copy);
    }

    // Identity match, so adding one listener twice gives two registrations that each remove one.
    private static <T> List<T> without(List<T> list, T item) {
        List<T> copy = new ArrayList<T>(list);
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i) == item) {
                copy.remove(i);
                break;
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static final class OnceRegistration implements Registration {
        private final AtomicBoolean isRemoved = new AtomicBoolean();
        private final Runnable action;

        OnceRegistration(Runnable action) {
            this.action = action;
        }

        @Override
        public void remove() {
            if (isRemoved.compareAndSet(false, true)) {
                action.run();
            }
        }
    }

    private static final class PerQueryHandler {
        final long thresholdNanos;
        final Consumer<QueryExecuted> handler;

        PerQueryHandler(long thresholdNanos, Consumer<QueryExecuted> handler) {
            this.thresholdNanos = thresholdNanos;
            this.handler = handler;
        }
    }

    private static final class TotalTimeHandler {
        final long thresholdNanos;
        final BiConsumer<Connection, QueryExecuted> handler;
        final AtomicBoolean hasRun = new AtomicBoolean();

        TotalTimeHandler(long thresholdNanos, BiConsumer<Connection, QueryExecuted> handler) {
            this.thresholdNanos = thresholdNanos;
            this.handler = handler;
        }
    }
}

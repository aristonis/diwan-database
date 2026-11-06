package org.aristonis.diwan.database.query;

import java.util.Iterator;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Streams the results of one read, one at a time, while its statement stays open.
 *
 * <p>A cursor is read once: {@link #iterator()} and {@link #stream()} share one single use, so a
 * second call to either, or a stream taken after the iterator, is refused. Reading the last
 * element closes the cursor, so a loop that runs to the end releases everything by itself; a loop
 * left early must call {@link #close()}, best through try-with-resources. {@link #map(Function)}
 * gives a view over the same rows that shares the single use and the close.
 *
 * <pre>{@code
 * try (QueryCursor<Map<String, Object>> rows = db.table("orders").orderBy("id").cursor()) {
 *     for (Map<String, Object> row : rows) {
 *         export(row);
 *     }
 * }
 * }</pre>
 *
 * <p>A cursor is not thread-safe and belongs to the thread that opened it.
 *
 * <p>Not for implementation outside Diwan: methods added later arrive as {@code default} methods.
 *
 * @param <T> the type of the elements
 */
public interface QueryCursor<T> extends Iterable<T>, AutoCloseable {

    /**
     * Returns the iterator over the elements, once.
     *
     * @return the iterator, never {@code null}
     * @throws IllegalStateException if the cursor, or a view of it, was already iterated or
     *     streamed
     */
    @Override
    Iterator<T> iterator();

    /**
     * Returns the elements as a lazy stream, once.
     *
     * <p>Closing the stream closes the cursor. A stream that is neither read to the end nor closed
     * keeps its connection, so use it in try-with-resources: a bare {@code stream().forEach(...)}
     * that stops early leaves the cursor open.
     *
     * @return the stream, never {@code null}
     * @throws IllegalStateException if the cursor, or a view of it, was already iterated or
     *     streamed
     */
    Stream<T> stream();

    /**
     * Returns a view of this cursor that maps every element.
     *
     * <p>The view reads the same rows: it shares the single use and the close with this cursor, so
     * closing either closes both. The mapper runs as each element is read; when it throws, the
     * cursor is closed and the exception is rethrown.
     *
     * @param <R> the type of the mapped elements
     * @param mapper the mapping, never {@code null}
     * @return the mapped view, never {@code null}
     * @throws NullPointerException if {@code mapper} is {@code null}
     */
    <R> QueryCursor<R> map(Function<? super T, ? extends R> mapper);

    /**
     * Releases the statement and everything the cursor holds; a second call does nothing.
     */
    @Override
    void close();

    /**
     * Tells whether the cursor was closed, by {@link #close()}, by reading the last element or by a
     * failed read. A view made by {@link #map(Function)} answers for the cursor it shares.
     *
     * @return {@code true} once the cursor is closed
     */
    boolean isClosed();
}

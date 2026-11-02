package org.aristonis.diwan.database.driver;

import java.util.Iterator;
import java.util.Map;

/**
 * Streams the rows of one read, one row at a time, while the statement stays open.
 *
 * <p>A cursor is iterated once: {@link #iterator()} returns the cursor itself on the first call and
 * refuses any later call. It moves through four states: created, open (iterating), consumed (the last
 * row was read) and closed. {@link #hasNext()} answering {@code false} closes the cursor, so a loop
 * that runs to the end releases everything by itself; a loop left early must call {@link #close()},
 * best through try-with-resources.
 *
 * <pre>{@code
 * try (RowCursor rows = connection.cursor("select * from orders order by id")) {
 *     for (Map<String, Object> row : rows) {
 *         export(row);
 *     }
 * }
 * }</pre>
 *
 * <p>A cursor is not thread-safe and belongs to the thread that opened it.
 *
 * @implSpec Rows must be in result order with columns in select order, keyed by the label as the
 *     database reports it, as for {@link DriverSession#query}. {@link #next()} after the last row
 *     must throw {@link java.util.NoSuchElementException}; after {@link #close()} it must throw
 *     {@link IllegalStateException}. {@link #hasNext()} after {@code close()} answers {@code false}.
 *     {@link #close()} must be idempotent and must release the statement even when an earlier step
 *     of closing failed. A failure while reading must be reported as a {@link DriverException}; the
 *     caller then closes the cursor.
 */
public interface RowCursor extends Iterator<Map<String, Object>>, Iterable<Map<String, Object>>, AutoCloseable {

    /**
     * Returns this cursor, once.
     *
     * @return this cursor, never {@code null}
     * @throws IllegalStateException if the cursor was already iterated
     */
    @Override
    Iterator<Map<String, Object>> iterator();

    /**
     * Tells whether another row follows, closing the cursor when none does.
     *
     * @return {@code true} if {@link #next()} returns a row; {@code false} at the end or after
     *     {@link #close()}
     * @throws DriverException if reading the next row fails (driver level)
     * @throws org.aristonis.diwan.database.QueryException if reading the next row fails (connection
     *     level; the cursor is then already closed)
     */
    @Override
    boolean hasNext();

    /**
     * Returns the next row.
     *
     * @return the row, columns in select order, never {@code null}
     * @throws java.util.NoSuchElementException if no row is left
     * @throws IllegalStateException if the cursor is closed
     * @throws DriverException if reading the row fails (driver level)
     * @throws org.aristonis.diwan.database.QueryException if reading the row fails (connection
     *     level; the cursor is then already closed)
     */
    @Override
    Map<String, Object> next();

    /**
     * Refuses to remove a row; a cursor only reads.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default void remove() {
        throw new UnsupportedOperationException("a cursor only reads rows");
    }

    /**
     * Releases the statement and everything the cursor holds; a second call does nothing.
     *
     * @throws DriverException if releasing fails (driver level); the statement is released anyway
     * @throws org.aristonis.diwan.database.QueryException if releasing fails (connection level);
     *     the session is released anyway
     */
    @Override
    void close();

    /**
     * Tells whether the cursor is closed, by {@link #close()} or by reaching the end.
     *
     * @return {@code true} once the cursor is closed
     */
    boolean isClosed();
}

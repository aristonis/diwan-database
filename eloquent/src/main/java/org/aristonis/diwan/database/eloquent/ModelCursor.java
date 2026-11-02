package org.aristonis.diwan.database.eloquent;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import org.aristonis.diwan.database.driver.RowCursor;

/**
 * Models streamed one at a time from an open database cursor.
 *
 * <p>Holds a database session until closed or read to the end; close it, best with
 * try-with-resources. Iterating it again continues where it stopped. Belongs to the calling
 * thread.
 *
 * @param <T> the model class
 */
public final class ModelCursor<T extends Model> implements Iterator<T>, Iterable<T>, AutoCloseable {

    private final RowCursor rows;
    private final Function<Map<String, Object>, T> hydrate;

    ModelCursor(RowCursor rows, Function<Map<String, Object>, T> hydrate) {
        this.rows = rows;
        this.hydrate = hydrate;
    }

    /**
     * Returns this cursor, so it can be used in a for-each loop.
     *
     * @return this cursor
     */
    @Override
    public Iterator<T> iterator() {
        return this;
    }

    /**
     * Returns whether another model follows; closes the cursor after the last one.
     *
     * @return whether a model follows; {@code false} once closed
     * @throws org.aristonis.diwan.database.QueryException if reading fails
     */
    @Override
    public boolean hasNext() {
        return !rows.isClosed() && rows.hasNext();
    }

    /**
     * Returns the next model, built from the next row.
     *
     * @return the model, never {@code null}
     * @throws NoSuchElementException if no model follows
     * @throws RuntimeException whatever building the model throws, after the cursor is closed
     * @throws org.aristonis.diwan.database.QueryException if reading fails
     */
    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        Map<String, Object> row = rows.next();
        try {
            return hydrate.apply(row);
        } catch (RuntimeException | Error e) {
            // A failed hydration ends the iteration, so the session is given back now rather
            // than left to a caller that may not reach close().
            rows.close();
            throw e;
        }
    }

    /**
     * Closes the cursor and gives back its session; safe to call more than once.
     */
    @Override
    public void close() {
        rows.close();
    }

    /**
     * Returns whether the cursor is closed.
     *
     * @return whether it is closed
     */
    public boolean isClosed() {
        return rows.isClosed();
    }
}

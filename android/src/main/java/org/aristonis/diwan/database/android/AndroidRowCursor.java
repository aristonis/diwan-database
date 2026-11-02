package org.aristonis.diwan.database.android;

import android.database.Cursor;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import org.aristonis.diwan.database.driver.RowCursor;

/**
 * Streams the rows of one Android {@link Cursor}, reading one row ahead, without a transaction.
 *
 * <p>The cursor holds a window of rows; when the loop passes its end, Android refills the window by
 * running the query again from the new position. Rows inserted, deleted or reordered by the loop or
 * by anyone else in the meantime can therefore be skipped or read twice. For big tables, or tables
 * that change while they are read, use {@code lazyById} or {@code chunkById}, which page by key.
 *
 * <p>The cursor belongs to the thread that opened it: {@link #hasNext()}, {@link #next()} and
 * {@link #close()} from any other thread throw {@link IllegalStateException}.
 */
final class AndroidRowCursor implements RowCursor {

    private enum State { OPEN, CONSUMED, CLOSED }

    private final Cursor cursor;
    private final String[] labels;
    private final Thread owner;
    private State state = State.OPEN;
    private boolean isIterated;
    private Map<String, Object> ahead;

    AndroidRowCursor(Cursor cursor, String[] labels, Thread owner) {
        this.cursor = cursor;
        this.labels = labels.clone();
        this.owner = owner;
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
        requireOwner();
        if (state != State.OPEN) {
            return false;
        }
        if (ahead != null) {
            return true;
        }
        boolean hasRow;
        try {
            hasRow = cursor.moveToNext();
            if (hasRow) {
                ahead = Collections.unmodifiableMap(AndroidSqliteSession.readRow(cursor, labels));
            }
        } catch (RuntimeException e) {
            throw AndroidSqliteSession.translate(e);
        }
        if (!hasRow) {
            state = State.CONSUMED;
            release();
        }
        return hasRow;
    }

    @Override
    public Map<String, Object> next() {
        requireOwner();
        if (state == State.CLOSED) {
            throw new IllegalStateException("The cursor is closed");
        }
        if (!hasNext()) {
            throw new NoSuchElementException("The cursor has no more rows");
        }
        Map<String, Object> row = ahead;
        ahead = null;
        return row;
    }

    @Override
    public void close() {
        requireOwner();
        if (state != State.OPEN) {
            return;
        }
        state = State.CLOSED;
        ahead = null;
        release();
    }

    @Override
    public boolean isClosed() {
        return state != State.OPEN;
    }

    private void release() {
        try {
            cursor.close();
        } catch (RuntimeException e) {
            throw AndroidSqliteSession.translate(e);
        }
    }

    private void requireOwner() {
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException("The cursor belongs to thread " + owner.getName()
                    + "; use it only on that thread");
        }
    }
}

package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One page of rows read by key position, with the cursors that lead to the pages around it, as
 * {@link Builder#cursorPaginate(int, Cursor)} returns it; Laravel's {@code CursorPaginator} without
 * the URL and HTML parts.
 *
 * <p>Immutable and safe to share between threads; the rows are unmodifiable.
 */
public final class CursorPage {

    private final List<Map<String, Object>> items;
    private final int perPage;
    private final Cursor nextCursor;
    private final Cursor previousCursor;

    CursorPage(List<Map<String, Object>> items, int perPage, Cursor nextCursor, Cursor previousCursor) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.perPage = perPage;
        this.nextCursor = nextCursor;
        this.previousCursor = previousCursor;
    }

    /**
     * Returns the rows of this page, in the query's order also when it was read backwards.
     *
     * @return at most {@link #perPage()} rows, unmodifiable, never {@code null}
     */
    public List<Map<String, Object>> items() {
        return items;
    }

    /**
     * Returns the number of rows a page holds.
     *
     * @return the page size, at least 1
     */
    public int perPage() {
        return perPage;
    }

    /**
     * Returns the cursor of the page after this one.
     *
     * @return the cursor, empty on the last page
     */
    public Optional<Cursor> nextCursor() {
        return Optional.ofNullable(nextCursor);
    }

    /**
     * Returns the cursor of the page before this one.
     *
     * @return the cursor, empty on the first page
     */
    public Optional<Cursor> previousCursor() {
        return Optional.ofNullable(previousCursor);
    }

    @Override
    public String toString() {
        return "CursorPage[" + items.size() + " rows, next: " + (nextCursor != null) + ", previous: "
                + (previousCursor != null) + "]";
    }
}

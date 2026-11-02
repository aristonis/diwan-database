package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One page of rows with the total number of matching rows, as {@link Builder#paginate(int, int)}
 * returns it; Laravel's {@code LengthAwarePaginator} without the URL and HTML parts.
 *
 * <p>Immutable and safe to share between threads; the rows are unmodifiable.
 */
public final class LengthAwarePage {

    private final List<Map<String, Object>> items;
    private final long total;
    private final int perPage;
    private final int currentPage;
    private final int lastPage;

    LengthAwarePage(List<Map<String, Object>> items, long total, int perPage, int currentPage) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.total = total;
        this.perPage = perPage;
        this.currentPage = currentPage;
        // Laravel: max((int) ceil($total / $perPage), 1).
        this.lastPage = (int) Math.max((total + perPage - 1) / perPage, 1);
    }

    /**
     * Returns the rows of this page.
     *
     * @return the rows in query order, unmodifiable, never {@code null}, empty past the last page
     */
    public List<Map<String, Object>> items() {
        return items;
    }

    /**
     * Returns the number of rows on every page together.
     *
     * @return the total, never negative
     */
    public long total() {
        return total;
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
     * Returns the number of this page, counting from 1.
     *
     * @return the page number, at least 1
     */
    public int currentPage() {
        return currentPage;
    }

    /**
     * Returns the number of the last page; 1 when no row matches.
     *
     * @return the last page number, at least 1
     */
    public int lastPage() {
        return lastPage;
    }

    /**
     * Tells whether a page follows this one.
     *
     * @return {@code true} if this page comes before the last page
     */
    public boolean hasMorePages() {
        return currentPage < lastPage;
    }

    @Override
    public String toString() {
        return "LengthAwarePage[page " + currentPage + " of " + lastPage + ", " + items.size() + " of " + total
                + " rows]";
    }
}

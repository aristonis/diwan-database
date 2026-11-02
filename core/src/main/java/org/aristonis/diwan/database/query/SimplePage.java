package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One page of rows and whether more follow, as {@link Builder#simplePaginate(int, int)} returns it;
 * Laravel's {@code Paginator} without the URL and HTML parts. No count query runs for it.
 *
 * <p>Immutable and safe to share between threads; the rows are unmodifiable.
 */
public final class SimplePage {

    private final List<Map<String, Object>> items;
    private final int perPage;
    private final int currentPage;
    private final boolean hasMorePages;

    SimplePage(List<Map<String, Object>> items, int perPage, int currentPage, boolean hasMorePages) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.perPage = perPage;
        this.currentPage = currentPage;
        this.hasMorePages = hasMorePages;
    }

    /**
     * Returns the rows of this page.
     *
     * @return at most {@link #perPage()} rows in query order, unmodifiable, never {@code null}
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
     * Returns the number of this page, counting from 1.
     *
     * @return the page number, at least 1
     */
    public int currentPage() {
        return currentPage;
    }

    /**
     * Tells whether at least one row follows this page.
     *
     * @return {@code true} if a next page has rows
     */
    public boolean hasMorePages() {
        return hasMorePages;
    }

    @Override
    public String toString() {
        return "SimplePage[page " + currentPage + ", " + items.size() + " rows, more: " + hasMorePages + "]";
    }
}

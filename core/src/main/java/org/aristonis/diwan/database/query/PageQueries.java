package org.aristonis.diwan.database.query;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.Connection;

/**
 * The pagination layer of {@link Builder}: pages by number with a total ({@code paginate}), pages by
 * number without one ({@code simplePaginate}), and pages by key position ({@code cursorPaginate}).
 * Each runs on copies, so the builder is unchanged. The results are plain page objects; building
 * URLs or HTML is left to the application.
 */
abstract class PageQueries extends ChunkQueries {

    PageQueries(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    /**
     * Returns the first page of {@code perPage} rows with the total, as for
     * {@link Builder#paginate(int, int, List)}.
     *
     * @param perPage the rows per page, at least 1
     * @return the page, never {@code null}
     * @throws IllegalArgumentException if {@code perPage} is below 1
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a query
     */
    public final LengthAwarePage paginate(int perPage) {
        return paginate(perPage, 1);
    }

    /**
     * Returns one page of rows with the total, as for {@link Builder#paginate(int, int, List)}.
     *
     * @param perPage the rows per page, at least 1
     * @param page the page number, from 1
     * @return the page, never {@code null}
     * @throws IllegalArgumentException if {@code perPage} or {@code page} is below 1
     * @throws ArithmeticException if the page's offset does not fit in an {@code int}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a query
     */
    public final LengthAwarePage paginate(int perPage, int page) {
        return paginate(perPage, page, Collections.<String>emptyList());
    }

    /**
     * Returns one page of rows with the total. The total comes first, from
     * {@link Builder#getCountForPagination()}; when it is 0 the rows are not read. The page reads
     * {@code limit perPage offset (page - 1) * perPage} and is empty past the last page. Without
     * an order the rows of a page are whatever the database returns first.
     *
     * @param perPage the rows per page, at least 1
     * @param page the page number, from 1
     * @param columns the columns to select when the query selects none, never {@code null}; empty
     *     selects every column
     * @return the page, never {@code null}
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if {@code perPage} or {@code page} is below 1
     * @throws ArithmeticException if the page's offset does not fit in an {@code int}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a query
     */
    public final LengthAwarePage paginate(int perPage, int page, List<String> columns) {
        checkPage(perPage, page);
        Objects.requireNonNull(columns, "columns");
        long total = getCountForPagination();
        List<Map<String, Object>> items = total > 0
                ? withColumns(self().clone(), columns).forPage(page, perPage).get()
                : Collections.<Map<String, Object>>emptyList();
        return new LengthAwarePage(items, total, perPage, page);
    }

    /**
     * Returns the first page of {@code perPage} rows and whether more follow.
     *
     * @param perPage the rows per page, at least 1
     * @return the page, never {@code null}
     * @throws IllegalArgumentException if {@code perPage} is below 1
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final SimplePage simplePaginate(int perPage) {
        return simplePaginate(perPage, 1);
    }

    /**
     * Returns one page of rows and whether more follow, as for
     * {@link Builder#simplePaginate(int, int, List)}.
     *
     * @param perPage the rows per page, at least 1
     * @param page the page number, from 1
     * @return the page, never {@code null}
     * @throws IllegalArgumentException if {@code perPage} or {@code page} is below 1
     * @throws ArithmeticException if the page's offset does not fit in an {@code int}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final SimplePage simplePaginate(int perPage, int page) {
        return simplePaginate(perPage, page, Collections.<String>emptyList());
    }

    /**
     * Returns one page of rows and whether more follow, without a count query: it reads
     * {@code perPage + 1} rows and keeps {@code perPage}, as Laravel does.
     *
     * @param perPage the rows per page, at least 1
     * @param page the page number, from 1
     * @param columns the columns to select when the query selects none, never {@code null}; empty
     *     selects every column
     * @return the page, never {@code null}
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if {@code perPage} or {@code page} is below 1
     * @throws ArithmeticException if the page's offset does not fit in an {@code int}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final SimplePage simplePaginate(int perPage, int page, List<String> columns) {
        checkPage(perPage, page);
        Objects.requireNonNull(columns, "columns");
        List<Map<String, Object>> rows = withColumns(self().clone(), columns)
                .offset(Math.multiplyExact(page - 1, perPage)).limit(Math.addExact(perPage, 1)).get();
        boolean hasMore = rows.size() > perPage;
        return new SimplePage(hasMore ? rows.subList(0, perPage) : rows, perPage, page, hasMore);
    }

    /**
     * Returns the first page of {@code perPage} rows by key position.
     *
     * @param perPage the rows per page, at least 1
     * @return the page, as for {@link Builder#cursorPaginate(int, Cursor, List)}
     * @throws IllegalArgumentException if {@code perPage} is below 1
     * @throws IllegalStateException if the query is not ordered by a column
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final CursorPage cursorPaginate(int perPage) {
        return cursorPaginate(perPage, null);
    }

    /**
     * Returns the page a cursor points to, as for {@link Builder#cursorPaginate(int, Cursor, List)}.
     *
     * @param perPage the rows per page, at least 1
     * @param cursor the cursor from an earlier page, {@code null} for the first page
     * @return the page, never {@code null}
     * @throws IllegalArgumentException if {@code perPage} is below 1, or the cursor's parameters are
     *     not the query's order columns
     * @throws IllegalStateException if the query is not ordered by a column
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final CursorPage cursorPaginate(int perPage, Cursor cursor) {
        return cursorPaginate(perPage, cursor, Collections.<String>emptyList());
    }

    /**
     * Returns the page a cursor points to, reading by key position instead of by offset, so rows
     * added or removed between requests never shift a page. The query's column orders are the key:
     * the page after a cursor reads {@code (c1 > ? or (c1 = ? and (c2 > ? ...)))}, with {@code <}
     * for a descending column, and the page before it reads with every order flipped and gives the
     * rows back in the query's order. A select alias ({@code "users.id as uid"} ordered by
     * {@code uid}) is resolved to its column. {@code perPage + 1} rows are read to know whether
     * another page follows.
     *
     * @apiNote The order columns should together be unique and never {@code null}: rows that tie
     *     on every order column, or hold {@code null} in one, can be skipped, as in Laravel. Order by
     *     a unique key last.
     * @param perPage the rows per page, at least 1
     * @param cursor the cursor from an earlier page, {@code null} for the first page
     * @param columns the columns to select when the query selects none, never {@code null}; empty
     *     selects every column; the rows must hold every order column
     * @return the page, never {@code null}
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if {@code perPage} is below 1, the cursor's parameters are
     *     not the query's order columns, or a row's order value cannot be kept in a {@link Cursor}
     * @throws IllegalStateException if the query is not ordered by a column: "You must specify an
     *     orderBy clause when using this function."
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final CursorPage cursorPaginate(int perPage, Cursor cursor, List<String> columns) {
        checkPage(perPage, 1);
        Objects.requireNonNull(columns, "columns");
        Builder copy = self().clone();
        List<Order.Column> orders = PaginationOrders.prepare(copy, cursor != null && cursor.pointsToPreviousItems());
        if (cursor != null) {
            PaginationOrders.requireMatching(cursor, orders);
            PaginationOrders.addConditions(copy, orders, cursor);
        }
        List<Map<String, Object>> rows = withColumns(copy, columns).limit(Math.addExact(perPage, 1)).get();
        return PaginationOrders.page(rows, perPage, cursor, orders);
    }

    private static void checkPage(int perPage, int page) {
        if (perPage < 1 || page < 1) {
            throw new IllegalArgumentException("The page and the rows per page must be at least 1");
        }
    }

    // Laravel's get($columns) selects them only when the query selects nothing.
    private static Builder withColumns(Builder copy, List<String> columns) {
        if (copy.columns.isEmpty() && !columns.isEmpty()) {
            for (String column : columns) {
                copy.columns.add(Objects.requireNonNull(column, "column"));
            }
        }
        return copy;
    }
}

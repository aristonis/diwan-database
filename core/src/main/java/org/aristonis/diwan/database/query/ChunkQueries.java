package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.aristonis.diwan.database.Connection;

/**
 * The chunking layer of {@link Builder}: reading a large result in pages ({@code chunk},
 * {@code each}, {@code chunkById}, {@code lazy}) or as a stream ({@code cursor}).
 *
 * <p>Every page runs on a copy, so the builder is unchanged afterwards; Laravel moves the offset
 * and limit of the builder itself. A limit or offset already on the query is honored: the pages
 * together never read more rows than the limit.
 *
 * <p>Offset pages ({@code chunk}, {@code each}, {@code chunkMap}, {@code lazy}) need an order and
 * can skip or repeat rows when rows are added or removed while paging; key pages
 * ({@code chunkById}, {@code eachById}, {@code lazyById}) add their own order on the key and do not.
 */
abstract class ChunkQueries extends WriteQueries {

    private static final String CHUNK_BY_ID = "chunkById";
    private static final String LAZY_BY_ID = "lazyById";

    ChunkQueries(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    // ---- offset pages ----

    /**
     * Runs the query in pages of {@code count} rows, by offset and limit, and hands each page to
     * the callback until it returns {@code false} or a page comes back short.
     *
     * @param count the rows per page, at least 1
     * @param callback receives each page, unmodifiable, in order; returns {@code false} to stop,
     *     never {@code null}
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if the query has no order: "You must specify an orderBy
     *     clause when using this function."
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean chunk(int count, Predicate<List<Map<String, Object>>> callback) {
        Objects.requireNonNull(callback, "callback");
        ChunkSupport.requirePositive(count);
        ChunkSupport.enforceOrderBy(this);
        return runPages(new ChunkSupport.OffsetPager(self().clone(), count), callback);
    }

    /**
     * Hands every row to the callback, reading 1000 rows per page, until it returns {@code false}.
     *
     * @param callback receives each row in order; returns {@code false} to stop, never {@code null}
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalStateException if the query has no order
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean each(Predicate<Map<String, Object>> callback) {
        return each(callback, ChunkSupport.DEFAULT_COUNT);
    }

    /**
     * Hands every row to the callback, reading {@code count} rows per page, until it returns
     * {@code false}.
     *
     * @param callback receives each row in order; returns {@code false} to stop, never {@code null}
     * @param count the rows per page, at least 1
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if the query has no order
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean each(Predicate<Map<String, Object>> callback, int count) {
        Objects.requireNonNull(callback, "callback");
        return chunk(count, rows -> allAccepted(rows, callback));
    }

    /**
     * Maps every row, reading 1000 rows per page, and returns the results.
     *
     * @param <T> the result type
     * @param callback maps one row, never {@code null}
     * @return the results in row order, unmodifiable, never {@code null}, may contain {@code null}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalStateException if the query has no order
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final <T> List<T> chunkMap(Function<Map<String, Object>, T> callback) {
        return chunkMap(callback, ChunkSupport.DEFAULT_COUNT);
    }

    /**
     * Maps every row, reading {@code count} rows per page, and returns the results.
     *
     * @param <T> the result type
     * @param callback maps one row, never {@code null}
     * @param count the rows per page, at least 1
     * @return the results in row order, unmodifiable, never {@code null}, may contain {@code null}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if the query has no order
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final <T> List<T> chunkMap(Function<Map<String, Object>, T> callback, int count) {
        Objects.requireNonNull(callback, "callback");
        List<T> results = new ArrayList<>();
        chunk(count, rows -> {
            for (Map<String, Object> row : rows) {
                results.add(callback.apply(row));
            }
            return true;
        });
        return Collections.unmodifiableList(results);
    }

    /**
     * Returns the rows as an iterable that reads 1000 rows per page, a page at a time.
     *
     * @return the rows, as for {@link Builder#lazy(int)}
     * @throws IllegalStateException if the query has no order
     */
    public final Iterable<Map<String, Object>> lazy() {
        return lazy(ChunkSupport.DEFAULT_COUNT);
    }

    /**
     * Returns the rows as an iterable that reads {@code chunkSize} rows per page, running the next
     * page only when the previous one is used up. No connection is held between pages. The query
     * is copied now; each {@code iterator()} runs it again from the first page.
     *
     * @param chunkSize the rows per page, at least 1
     * @return the rows in order, never {@code null}; the iterator's {@code hasNext} and
     *     {@code next} may throw {@link org.aristonis.diwan.database.exception.QueryException}
     * @throws IllegalArgumentException if {@code chunkSize} is below 1
     * @throws IllegalStateException if the query has no order
     */
    public final Iterable<Map<String, Object>> lazy(int chunkSize) {
        ChunkSupport.requirePositive(chunkSize);
        ChunkSupport.enforceOrderBy(this);
        Builder snapshot = self().clone();
        return () -> new ChunkSupport.PagedIterator(new ChunkSupport.OffsetPager(snapshot, chunkSize));
    }

    // ---- key pages ----

    /**
     * Runs the query in pages of {@code count} rows ordered by {@code id}, each page reading the
     * rows after the last key of the one before.
     *
     * @param count the rows per page, at least 1
     * @param callback receives each page, as for {@link Builder#chunk}, never {@code null}
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if a page's last row has no {@code id} value
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean chunkById(int count, Predicate<List<Map<String, Object>>> callback) {
        return chunkById(count, callback, DEFAULT_KEY, DEFAULT_KEY);
    }

    /**
     * Runs the query in pages ordered by a key column, read back from the rows under the same name.
     *
     * @param count the rows per page, at least 1
     * @param callback receives each page, never {@code null}
     * @param column the key column, never {@code null}
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if a page's last row has no value under {@code column}
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean chunkById(int count, Predicate<List<Map<String, Object>>> callback, String column) {
        return chunkById(count, callback, column, column);
    }

    /**
     * Runs the query in pages ordered by a key column, reading the last key of each page from the
     * rows under {@code alias}: for a join, the column is {@code "users.id"} and the alias the name
     * the select gives it, such as {@code "id"}. The query's own offset applies to the first page
     * only, as in Laravel.
     *
     * @param count the rows per page, at least 1
     * @param callback receives each page, never {@code null}
     * @param column the key column, never {@code null}
     * @param alias the key's name in the rows, never {@code null}
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if a page's last row has no value under {@code alias}
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean chunkById(int count, Predicate<List<Map<String, Object>>> callback, String column,
            String alias) {
        return chunkByKey(count, callback, column, alias, false);
    }

    /**
     * Runs the query in pages ordered by {@code id}, highest first.
     *
     * @param count the rows per page, at least 1
     * @param callback receives each page, never {@code null}
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if a page's last row has no {@code id} value
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean chunkByIdDesc(int count, Predicate<List<Map<String, Object>>> callback) {
        return chunkByIdDesc(count, callback, DEFAULT_KEY, DEFAULT_KEY);
    }

    /**
     * Runs the query in pages ordered by a key column, highest first.
     *
     * @param count the rows per page, at least 1
     * @param callback receives each page, never {@code null}
     * @param column the key column, never {@code null}
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if a page's last row has no value under {@code column}
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean chunkByIdDesc(int count, Predicate<List<Map<String, Object>>> callback, String column) {
        return chunkByIdDesc(count, callback, column, column);
    }

    /**
     * Runs the query in pages ordered by a key column, highest first, reading the key under
     * {@code alias}, as for {@link Builder#chunkById(int, Predicate, String, String)}.
     *
     * @param count the rows per page, at least 1
     * @param callback receives each page, never {@code null}
     * @param column the key column, never {@code null}
     * @param alias the key's name in the rows, never {@code null}
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if a page's last row has no value under {@code alias}
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean chunkByIdDesc(int count, Predicate<List<Map<String, Object>>> callback, String column,
            String alias) {
        return chunkByKey(count, callback, column, alias, true);
    }

    /**
     * Hands every row to the callback, by {@code id} pages of 1000, until it returns {@code false}.
     *
     * @param callback receives each row in key order; returns {@code false} to stop, never
     *     {@code null}
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalStateException if a page's last row has no {@code id} value
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean eachById(Predicate<Map<String, Object>> callback) {
        return eachById(callback, ChunkSupport.DEFAULT_COUNT);
    }

    /**
     * Hands every row to the callback, by {@code id} pages of {@code count}, until it returns
     * {@code false}.
     *
     * @param callback receives each row in key order; returns {@code false} to stop, never
     *     {@code null}
     * @param count the rows per page, at least 1
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if a page's last row has no {@code id} value
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean eachById(Predicate<Map<String, Object>> callback, int count) {
        return eachById(callback, count, DEFAULT_KEY, DEFAULT_KEY);
    }

    /**
     * Hands every row to the callback, by key pages, reading the key under {@code alias}.
     *
     * @param callback receives each row in key order; returns {@code false} to stop, never
     *     {@code null}
     * @param count the rows per page, at least 1
     * @param column the key column, never {@code null}
     * @param alias the key's name in the rows, never {@code null}
     * @return {@code false} if the callback stopped the run, else {@code true}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code count} is below 1
     * @throws IllegalStateException if a page's last row has no value under {@code alias}
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a page
     */
    public final boolean eachById(Predicate<Map<String, Object>> callback, int count, String column, String alias) {
        Objects.requireNonNull(callback, "callback");
        return chunkById(count, rows -> allAccepted(rows, callback), column, alias);
    }

    /**
     * Returns the rows as an iterable that reads {@code id} pages of {@code chunkSize}.
     *
     * @param chunkSize the rows per page, at least 1
     * @return the rows in key order, as for {@link Builder#lazyById(int, String, String)}
     * @throws IllegalArgumentException if {@code chunkSize} is below 1
     */
    public final Iterable<Map<String, Object>> lazyById(int chunkSize) {
        return lazyById(chunkSize, DEFAULT_KEY, DEFAULT_KEY);
    }

    /**
     * Returns the rows as an iterable that reads key pages, the key read back under its own name.
     *
     * @param chunkSize the rows per page, at least 1
     * @param column the key column, never {@code null}
     * @return the rows in key order, as for {@link Builder#lazyById(int, String, String)}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code chunkSize} is below 1
     */
    public final Iterable<Map<String, Object>> lazyById(int chunkSize, String column) {
        return lazyById(chunkSize, column, column);
    }

    /**
     * Returns the rows as an iterable that reads key pages of {@code chunkSize}, the next page only
     * when the previous one is used up; no connection is held between pages. The query is copied
     * now; each {@code iterator()} runs it again from the first page.
     *
     * @param chunkSize the rows per page, at least 1
     * @param column the key column, never {@code null}
     * @param alias the key's name in the rows, never {@code null}
     * @return the rows in key order, never {@code null}; the iterator throws
     *     {@link IllegalStateException} when a full page's last row has no value under
     *     {@code alias}, and may throw {@link org.aristonis.diwan.database.exception.QueryException}
     * @throws NullPointerException if {@code column} or {@code alias} is {@code null}
     * @throws IllegalArgumentException if {@code chunkSize} is below 1
     */
    public final Iterable<Map<String, Object>> lazyById(int chunkSize, String column, String alias) {
        return lazyByKey(chunkSize, column, alias, false);
    }

    /**
     * Returns the rows as an iterable that reads {@code id} pages, highest first.
     *
     * @param chunkSize the rows per page, at least 1
     * @return the rows in descending key order, as for {@link Builder#lazyById(int, String, String)}
     * @throws IllegalArgumentException if {@code chunkSize} is below 1
     */
    public final Iterable<Map<String, Object>> lazyByIdDesc(int chunkSize) {
        return lazyByIdDesc(chunkSize, DEFAULT_KEY, DEFAULT_KEY);
    }

    /**
     * Returns the rows as an iterable that reads key pages, highest first.
     *
     * @param chunkSize the rows per page, at least 1
     * @param column the key column, never {@code null}
     * @return the rows in descending key order, as for {@link Builder#lazyById(int, String, String)}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code chunkSize} is below 1
     */
    public final Iterable<Map<String, Object>> lazyByIdDesc(int chunkSize, String column) {
        return lazyByIdDesc(chunkSize, column, column);
    }

    /**
     * Returns the rows as an iterable that reads key pages, highest first, the key read under
     * {@code alias}.
     *
     * @param chunkSize the rows per page, at least 1
     * @param column the key column, never {@code null}
     * @param alias the key's name in the rows, never {@code null}
     * @return the rows in descending key order, as for {@link Builder#lazyById(int, String, String)}
     * @throws NullPointerException if {@code column} or {@code alias} is {@code null}
     * @throws IllegalArgumentException if {@code chunkSize} is below 1
     */
    public final Iterable<Map<String, Object>> lazyByIdDesc(int chunkSize, String column, String alias) {
        return lazyByKey(chunkSize, column, alias, true);
    }

    // ---- stream ----

    /**
     * Runs the query and streams its rows through a cursor, as {@link Connection#cursor(String,
     * List)} describes: the cursor must be closed, best with try-with-resources, and closes itself
     * after the last row. Unlike the page methods it needs no order. The rows are not passed through
     * the {@link Processor}.
     *
     * <p>The cursor holds its session until it is closed, so a long loop over it leaves a PostgreSQL
     * connection idle in transaction. Writing through the same database inside the loop needs a
     * second connection, and a pool of one deadlocks. Use {@link #lazy(int)} for a slow loop or one
     * that writes.
     *
     * <pre>{@code
     * try (QueryCursor<Map<String, Object>> rows = db.table("orders").orderBy("id").cursor()) {
     *     for (Map<String, Object> row : rows) {
     *         export(row);
     *     }
     * }
     * }</pre>
     *
     * @return the open cursor, never {@code null}
     * @throws IllegalStateException if a cursor is already open on the connection's session, or the
     *     query has a row lock that needs a transaction and none is open
     * @throws UnsupportedOperationException in pretend mode, or if the driver cannot stream
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the query
     */
    public final QueryCursor<Map<String, Object>> cursor() {
        checkLockTransaction();
        return connection.cursor(toSql(), grammar.prepareBindings(getBindings()));
    }

    private boolean chunkByKey(int count, Predicate<List<Map<String, Object>>> callback, String column, String alias,
            boolean isDescending) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(alias, "alias");
        ChunkSupport.requirePositive(count);
        ChunkSupport.IdPager pager = new ChunkSupport.IdPager(self().clone(), count, column, alias, isDescending,
                CHUNK_BY_ID);
        for (List<Map<String, Object>> rows = pager.next(); !rows.isEmpty(); rows = pager.next()) {
            if (!callback.test(rows)) {
                return false;
            }
            // Laravel checks the key after every page, the last one included.
            pager.requireLastId(rows);
        }
        return true;
    }

    private Iterable<Map<String, Object>> lazyByKey(int chunkSize, String column, String alias,
            boolean isDescending) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(alias, "alias");
        ChunkSupport.requirePositive(chunkSize);
        Builder snapshot = self().clone();
        return () -> new ChunkSupport.PagedIterator(
                new ChunkSupport.IdPager(snapshot, chunkSize, column, alias, isDescending, LAZY_BY_ID));
    }

    private static boolean runPages(ChunkSupport.Pager pager, Predicate<List<Map<String, Object>>> callback) {
        for (List<Map<String, Object>> rows = pager.next(); !rows.isEmpty(); rows = pager.next()) {
            if (!callback.test(rows)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allAccepted(List<Map<String, Object>> rows, Predicate<Map<String, Object>> callback) {
        for (Map<String, Object> row : rows) {
            if (!callback.test(row)) {
                return false;
            }
        }
        return true;
    }
}

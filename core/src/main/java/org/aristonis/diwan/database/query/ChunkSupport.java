package org.aristonis.diwan.database.query;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The page loops behind {@link Builder#chunk}, {@link Builder#chunkById}, {@link Builder#lazy} and
 * {@link Builder#lazyById}, as Laravel's {@code BuildsQueries} runs them. Every page runs on a fresh
 * copy of a snapshot of the caller's builder, so the caller's builder is never changed; Laravel sets
 * the offset and limit on the builder itself.
 */
final class ChunkSupport {

    static final int DEFAULT_COUNT = 1000;
    static final String ORDER_MESSAGE = "You must specify an orderBy clause when using this function.";

    private ChunkSupport() {
    }

    static void requirePositive(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("The chunk size should be at least 1");
        }
    }

    /** Refuses a query without an order, since pages of an unordered query may overlap. */
    static void enforceOrderBy(BuilderState query) {
        if (query.orders.isEmpty() && query.unionOrders.isEmpty()) {
            throw new IllegalStateException(ORDER_MESSAGE);
        }
    }

    // Laravel's getLimit and getOffset read the union's values once there is a union.
    static Integer currentLimit(BuilderState query) {
        return query.unions.isEmpty() ? query.limit : query.unionLimit;
    }

    static Integer currentOffset(BuilderState query) {
        return query.unions.isEmpty() ? query.offset : query.unionOffset;
    }

    /** Fetches one page at a time; an empty page means there are no more. */
    interface Pager {
        List<Map<String, Object>> next();
    }

    /** Pages by offset and limit, honoring the query's own offset and limit. */
    static final class OffsetPager implements Pager {

        private final Builder base;
        private final int count;
        private final int skip;
        private Integer remaining;
        private int page = 1;
        private boolean isDone;

        OffsetPager(Builder base, int count) {
            this.base = base;
            this.count = count;
            Integer offset = currentOffset(base);
            this.skip = offset == null ? 0 : offset;
            this.remaining = currentLimit(base);
        }

        @Override
        public List<Map<String, Object>> next() {
            int limit = remaining == null ? count : Math.min(count, remaining);
            if (isDone || limit == 0) {
                return Collections.emptyList();
            }
            int offset = Math.addExact(Math.multiplyExact(page - 1, count), skip);
            List<Map<String, Object>> rows = base.clone().offset(offset).limit(limit).get();
            if (remaining != null) {
                remaining = Math.max(remaining - rows.size(), 0);
            }
            isDone = rows.size() < count;
            page++;
            return rows;
        }
    }

    /**
     * Pages by key: each page reads the rows after (or before) the last key of the previous one.
     * The query's own offset applies to the first page only, as in Laravel.
     */
    static final class IdPager implements Pager {

        private final Builder base;
        private final int count;
        private final String column;
        private final String alias;
        private final boolean isDescending;
        private final String operation;
        private final boolean hasSkip;
        private Integer remaining;
        private Object lastId;
        private List<Map<String, Object>> previous;
        private int page = 1;
        private boolean isDone;

        IdPager(Builder base, int count, String column, String alias, boolean isDescending, String operation) {
            this.base = base;
            this.count = count;
            this.column = column;
            this.alias = alias;
            this.isDescending = isDescending;
            this.operation = operation;
            Integer offset = currentOffset(base);
            this.hasSkip = offset != null && offset > 0;
            this.remaining = currentLimit(base);
        }

        @Override
        public List<Map<String, Object>> next() {
            int limit = remaining == null ? count : Math.min(count, remaining);
            if (isDone || limit == 0) {
                return Collections.emptyList();
            }
            if (previous != null) {
                lastId = requireLastId(previous);
            }
            Builder copy = base.clone();
            if (hasSkip && page > 1) {
                copy.offset(0);
            }
            List<Map<String, Object>> rows = (isDescending
                    ? copy.forPageBeforeId(limit, lastId, column) : copy.forPageAfterId(limit, lastId, column)).get();
            if (remaining != null) {
                remaining = Math.max(remaining - rows.size(), 0);
            }
            isDone = rows.size() < count;
            previous = isDone ? null : rows;
            page++;
            return rows;
        }

        /** Reads the key of a page's last row, failing as Laravel does when it is missing or null. */
        Object requireLastId(List<Map<String, Object>> rows) {
            Object id = rows.get(rows.size() - 1).get(alias);
            if (id == null) {
                throw new IllegalStateException("The " + operation + " operation was aborted because the ["
                        + alias + "] column is not present in the query result.");
            }
            return id;
        }
    }

    /** Yields the rows of a pager one by one, fetching the next page only when the last is used up. */
    static final class PagedIterator implements Iterator<Map<String, Object>> {

        private final Pager pager;
        private Iterator<Map<String, Object>> page = Collections.<Map<String, Object>>emptyList().iterator();
        private boolean isDone;

        PagedIterator(Pager pager) {
            this.pager = pager;
        }

        @Override
        public boolean hasNext() {
            while (!page.hasNext() && !isDone) {
                List<Map<String, Object>> rows = pager.next();
                isDone = rows.isEmpty();
                page = rows.iterator();
            }
            return page.hasNext();
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) {
                throw new NoSuchElementException("No more rows");
            }
            return page.next();
        }
    }
}

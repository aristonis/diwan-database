package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Cursor;
import org.aristonis.diwan.database.query.CursorPage;
import org.aristonis.diwan.database.query.LengthAwarePage;
import org.aristonis.diwan.database.query.SimplePage;

/**
 * A query on one model's table that returns models: Laravel's Eloquent builder over the core
 * query builder.
 *
 * <p>Clause methods forward to the wrapped {@link Builder} and return this builder, so the SQL is
 * the query builder's. Read methods turn rows into models of {@code T} that exist, are clean and
 * have had their {@code retrieved()} hook run. Methods change this builder, as Laravel's do; start
 * a new one from {@link ModelQuery#query()} for each query. Not safe to share across threads.
 *
 * <p>Relations named with {@link #with(String...)} are eager loaded after each read that returns
 * models: {@link #get()}, {@link #first()} and the finds built on them, each chunk, each page and
 * each buffer of {@link #lazy(int)}. {@link #cursor()} refuses them.
 *
 * @param <T> the model class
 */
public final class EloquentBuilder<T extends Model> extends EloquentAggregates<T> {

    private static final int DEFAULT_CHUNK = 1000;

    EloquentBuilder(ModelQuery<T> source, T model) {
        this(source, model, model.newBaseQuery());
        attachClassScopes();
    }

    EloquentBuilder(ModelQuery<T> source, T model, Builder query) {
        super(source, model, query);
    }

    /**
     * Returns existing models built from rows, without a query. While
     * {@link ModelQuery#preventsLazyLoading()} is on and there is more than one row, each model is
     * marked so that a lazy relation read on it throws.
     *
     * @param rows the rows, never {@code null}
     * @return the models, in row order, never {@code null}
     */
    public ModelCollection<T> hydrate(List<? extends Map<String, ?>> rows) {
        List<T> models = new ArrayList<>(rows.size());
        // Laravel's Builder::hydrate: only a result of more than one row is marked
        boolean isMarked = rows.size() > 1 && ModelQuery.preventsLazyLoading();
        for (Map<String, ?> row : rows) {
            T model = hydrateOne(row);
            if (isMarked) {
                model.markLazyLoadingPrevented();
            }
            models.add(model);
        }
        return new ModelCollection<>(models, source);
    }

    private T hydrateOne(Map<String, ?> row) {
        T loaded = source.newModel();
        // Before hydrateFrom, so retrieved() already reads through the casts (Laravel newFromBuilder).
        Map<String, Cast<?>> casts = castsToMerge();
        if (!casts.isEmpty()) {
            loaded.mergeResolvedCasts(casts);
        }
        loaded.hydrateFrom(new LinkedHashMap<String, Object>(row));
        Consumer<? super T> hook = rowHook();
        if (hook != null) {
            hook.accept(loaded);
        }
        return loaded;
    }

    // ---- reads ----

    /**
     * Runs the query and returns its models.
     *
     * @return the models, never {@code null}
     */
    public ModelCollection<T> get() {
        return eagerLoaded(hydrate(scoped().get()));
    }

    /**
     * Returns the first matching model.
     *
     * @return the model, or empty
     */
    public Optional<T> first() {
        Optional<Map<String, Object>> row = scoped().first();
        if (!row.isPresent()) {
            return Optional.empty();
        }
        T found = hydrateOne(row.get());
        eagerLoaded(new ModelCollection<>(Collections.singletonList(found), source));
        return Optional.of(found);
    }

    /**
     * Returns the first matching model or throws.
     *
     * @return the model, never {@code null}
     * @throws ModelNotFoundException if none matches: {@code "No query results for model [Class]."}
     */
    public T firstOrFail() {
        Optional<T> found = first();
        if (!found.isPresent()) {
            throw notFound(Collections.emptyList());
        }
        return found.get();
    }

    /**
     * Returns the first matching model, or the callback's result when none matches.
     *
     * @param fallback gives the result when none matches, never {@code null}
     * @return the model or the fallback's result
     */
    public T firstOr(Supplier<T> fallback) {
        Optional<T> found = first();
        return found.isPresent() ? found.get() : fallback.get();
    }

    /**
     * Returns the first model where a column equals a value.
     *
     * @param column the column, never {@code null}
     * @param value the value
     * @return the model, or empty
     */
    public Optional<T> firstWhere(String column, Object value) {
        return where(column, value).first();
    }

    /**
     * Returns the first model where a column compares to a value.
     *
     * @param column the column, never {@code null}
     * @param operator the operator, never {@code null}
     * @param value the value
     * @return the model, or empty
     */
    public Optional<T> firstWhere(String column, String operator, Object value) {
        return where(column, operator, value).first();
    }

    /**
     * Returns the only matching model, reading at most two rows.
     *
     * @return the model, never {@code null}
     * @throws ModelNotFoundException if none matches
     * @throws MultipleRecordsFoundException if more than one matches
     */
    public T sole() {
        ModelCollection<T> found = take(2).get();
        if (found.isEmpty()) {
            throw notFound(Collections.emptyList());
        }
        if (found.size() > 1) {
            throw new MultipleRecordsFoundException(found.size());
        }
        return found.get(0);
    }

    /**
     * Finds a model by its primary key.
     *
     * <p>A collection of keys is refused: Laravel's {@code find} switches to {@code findMany} for
     * one, which a single return type cannot express here. {@link #find(Collection)} and
     * {@link #findMany(Collection)} take collections.
     *
     * @param id the key, or a model whose key to use
     * @return the model, or empty
     * @throws IllegalArgumentException if {@code id} is a {@link Collection}
     */
    public Optional<T> find(Object id) {
        if (id instanceof Collection) {
            throw new IllegalArgumentException("find() takes one key; use findMany() for a collection of keys");
        }
        return whereKey(id).first();
    }

    /**
     * Finds the models with the given keys, as {@link #findMany(Collection)}; the overload Laravel
     * reaches by passing an array to {@code find}.
     *
     * @param ids the keys, or models whose keys to use, never {@code null}
     * @return the models found, in the database's order, never {@code null}
     */
    public ModelCollection<T> find(Collection<?> ids) {
        return findMany(ids);
    }

    /**
     * Finds the models with the given keys; no query runs for an empty collection.
     *
     * @param ids the keys, never {@code null}
     * @return the models found, in the database's order, never {@code null}
     */
    public ModelCollection<T> findMany(Collection<?> ids) {
        if (ids.isEmpty()) {
            return new ModelCollection<>(new ArrayList<T>(), source);
        }
        return whereKey(ids).get();
    }

    /**
     * Finds a model by its primary key or throws.
     *
     * @param id the key
     * @return the model, never {@code null}
     * @throws ModelNotFoundException if there is none: {@code "No query results for model [Class] 5"}
     */
    public T findOrFail(Object id) {
        Optional<T> found = find(id);
        if (!found.isPresent()) {
            throw notFound(Collections.singletonList(id));
        }
        return found.get();
    }

    /**
     * Finds the models with the given keys, or throws naming the keys not found.
     *
     * @param ids the keys, never {@code null}
     * @return the models, never {@code null}
     * @throws ModelNotFoundException if any key is not found, listing those keys
     */
    public ModelCollection<T> findOrFail(Collection<?> ids) {
        ModelCollection<T> found = findMany(ids);
        Collection<Object> distinct = new LinkedHashSet<>(ids);
        if (found.size() == distinct.size()) {
            return found;
        }
        List<Object> missing = new ArrayList<>();
        for (Object id : distinct) {
            if (!found.find(id).isPresent()) {
                missing.add(id);
            }
        }
        throw notFound(missing);
    }

    /**
     * Finds a model by its primary key, or returns a new unsaved one.
     *
     * @param id the key
     * @return the model, never {@code null}
     */
    public T findOrNew(Object id) {
        Optional<T> found = find(id);
        return found.isPresent() ? found.get() : source.newModel();
    }

    /**
     * Finds a model by its primary key, or returns the callback's result.
     *
     * @param id the key
     * @param fallback gives the result when there is none, never {@code null}
     * @return the model or the fallback's result
     */
    public T findOr(Object id, Supplier<T> fallback) {
        Optional<T> found = find(id);
        return found.isPresent() ? found.get() : fallback.get();
    }

    /**
     * Returns one column of the first matching row.
     *
     * @param column the column, never {@code null}
     * @return the value, or empty when no row matches or the value is {@code null}
     */
    public Optional<Object> value(String column) {
        return scoped().value(column);
    }

    /**
     * Returns one column of every matching row.
     *
     * @param column the column, never {@code null}
     * @return the values, never {@code null}
     */
    public List<Object> pluck(String column) {
        return scoped().pluck(column);
    }

    /**
     * Counts the matching rows.
     *
     * @return the count
     */
    public long count() {
        return scoped().count();
    }

    /**
     * Returns whether any row matches.
     *
     * @return whether a row exists
     */
    public boolean exists() {
        return scoped().exists();
    }

    private ModelNotFoundException notFound(List<?> ids) {
        return new ModelNotFoundException(model.getClass(), ids);
    }

    // ---- chunks and streams ----

    // Orders the query a terminal runs on; with no scope attached that is this builder's own query.
    private Builder orderedByKey() {
        Builder ordered = scoped();
        if (ordered.getOrders().isEmpty() && ordered.getUnionOrders().isEmpty()) {
            ordered.orderBy(model.getQualifiedKeyName(), "asc");
        }
        return ordered;
    }

    /**
     * Reads the models in chunks by offset, ordered by the key when the query has no order.
     *
     * @param count the models per chunk, at least 1
     * @param callback takes each chunk; returning {@code false} stops, never {@code null}
     * @return {@code false} when the callback stopped early
     */
    public boolean chunk(int count, Predicate<ModelCollection<T>> callback) {
        return orderedByKey().chunk(count, rows -> callback.test(eagerLoaded(hydrate(rows))));
    }

    /**
     * Visits each model, reading in chunks of 1000.
     *
     * @param callback takes each model; returning {@code false} stops, never {@code null}
     * @return {@code false} when the callback stopped early
     */
    public boolean each(Predicate<T> callback) {
        return each(callback, DEFAULT_CHUNK);
    }

    /**
     * Visits each model, reading in chunks.
     *
     * @param callback takes each model; returning {@code false} stops, never {@code null}
     * @param count the models per chunk, at least 1
     * @return {@code false} when the callback stopped early
     */
    public boolean each(Predicate<T> callback, int count) {
        return chunk(count, models -> {
            for (T one : models) {
                if (!callback.test(one)) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * Reads the models in chunks by primary key, which stays correct while rows change.
     *
     * @param count the models per chunk, at least 1
     * @param callback takes each chunk; returning {@code false} stops, never {@code null}
     * @return {@code false} when the callback stopped early
     */
    public boolean chunkById(int count, Predicate<ModelCollection<T>> callback) {
        return chunkById(count, callback, model.getKeyName(), model.getKeyName());
    }

    /**
     * Reads the models in chunks by a column.
     *
     * @param count the models per chunk, at least 1
     * @param callback takes each chunk; returning {@code false} stops, never {@code null}
     * @param column the column to page by, never {@code null}
     * @param alias the column's name in the rows, never {@code null}
     * @return {@code false} when the callback stopped early
     */
    public boolean chunkById(int count, Predicate<ModelCollection<T>> callback, String column, String alias) {
        return scoped().chunkById(count, rows -> callback.test(eagerLoaded(hydrate(rows))), column, alias);
    }

    /**
     * Returns the models as a lazy sequence, read in chunks of 1000 by offset.
     *
     * @return the models, read as iterated, never {@code null}
     */
    public Iterable<T> lazy() {
        return lazy(DEFAULT_CHUNK);
    }

    /**
     * Returns the models as a lazy sequence, read in chunks by offset.
     *
     * @param count the rows per read, at least 1
     * @return the models, read as iterated, never {@code null}
     */
    public Iterable<T> lazy(int count) {
        return mapped(orderedByKey().lazy(count), count);
    }

    /**
     * Returns the models as a lazy sequence, read in chunks by primary key.
     *
     * @param count the rows per read, at least 1
     * @return the models, read as iterated, never {@code null}
     */
    public Iterable<T> lazyById(int count) {
        return lazyById(count, model.getKeyName(), model.getKeyName());
    }

    /**
     * Returns the models as a lazy sequence, read in chunks by a column.
     *
     * @param count the rows per read, at least 1
     * @param column the column to page by, never {@code null}
     * @param alias the column's name in the rows, never {@code null}
     * @return the models, read as iterated, never {@code null}
     */
    public Iterable<T> lazyById(int count, String column, String alias) {
        return mapped(scoped().lazyById(count, column, alias), count);
    }

    private Iterable<T> mapped(Iterable<Map<String, Object>> rows, int count) {
        // Lazy-load prevention marks per page, as Laravel's lazy() hydrates each page as a whole.
        if (hasEagerLoads() || ModelQuery.preventsLazyLoading()) {
            Map<String, Consumer<EloquentBuilder<?>>> eager = getEagerLoads();
            return () -> new EagerBuffer(rows.iterator(), count, eager);
        }
        return () -> {
            Iterator<Map<String, Object>> it = rows.iterator();
            return new Iterator<T>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public T next() {
                    return hydrateOne(it.next());
                }
            };
        };
    }

    // Reads count rows (the core reads pages of count), eager loads them as one collection, then
    // yields them, so a lazy sequence with eager loads runs one relation query per page.
    private final class EagerBuffer implements Iterator<T> {
        private final Iterator<Map<String, Object>> rows;
        private final int count;
        private final Map<String, Consumer<EloquentBuilder<?>>> eager;
        private Iterator<T> buffer = Collections.emptyIterator();

        EagerBuffer(Iterator<Map<String, Object>> rows, int count,
                Map<String, Consumer<EloquentBuilder<?>>> eager) {
            this.rows = rows;
            this.count = Math.max(1, count);
            this.eager = eager;
        }

        @Override
        public boolean hasNext() {
            if (!buffer.hasNext() && rows.hasNext()) {
                List<T> page = new ArrayList<>(count);
                while (page.size() < count && rows.hasNext()) {
                    page.add(hydrateOne(rows.next()));
                }
                if (page.size() > 1 && ModelQuery.preventsLazyLoading()) {
                    page.forEach(Model::markLazyLoadingPrevented);
                }
                eagerLoad(page, eager, false);
                buffer = page.iterator();
            }
            return buffer.hasNext();
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return buffer.next();
        }
    }

    /**
     * Streams the models through one open cursor; close it, best with try-with-resources.
     *
     * <p>Eager loads are refused: loading them per row would run one query per model, and Laravel
     * ignores them here. {@link #lazy(int)} reads in pages and eager loads each page.
     *
     * @return the open cursor, never {@code null}
     * @throws IllegalStateException if eager loads are set
     */
    public ModelCursor<T> cursor() {
        if (hasEagerLoads()) {
            throw new IllegalStateException("Eager loading is not supported with cursor(); use lazy()");
        }
        return new ModelCursor<>(scoped().cursor(), this::hydrateOne);
    }

    // ---- pages ----

    /**
     * Returns the first page, of the model's page size.
     *
     * @return the page, never {@code null}
     */
    public ModelPage<T> paginate() {
        return paginate(model.getPerPage(), 1);
    }

    /**
     * Returns the first page.
     *
     * @param perPage the models per page, at least 1
     * @return the page, never {@code null}
     */
    public ModelPage<T> paginate(int perPage) {
        return paginate(perPage, 1);
    }

    /**
     * Returns one page with the total count.
     *
     * @param perPage the models per page, at least 1
     * @param page the page number, from 1
     * @return the page, never {@code null}
     */
    public ModelPage<T> paginate(int perPage, int page) {
        LengthAwarePage rows = scoped().paginate(perPage, page);
        return new ModelPage<>(rows, eagerLoaded(hydrate(rows.items())));
    }

    /**
     * Returns the first page without a total, of the model's page size.
     *
     * @return the page, never {@code null}
     */
    public SimpleModelPage<T> simplePaginate() {
        return simplePaginate(model.getPerPage(), 1);
    }

    /**
     * Returns the first page without a total.
     *
     * @param perPage the models per page, at least 1
     * @return the page, never {@code null}
     */
    public SimpleModelPage<T> simplePaginate(int perPage) {
        return simplePaginate(perPage, 1);
    }

    /**
     * Returns one page without a total.
     *
     * @param perPage the models per page, at least 1
     * @param page the page number, from 1
     * @return the page, never {@code null}
     */
    public SimpleModelPage<T> simplePaginate(int perPage, int page) {
        SimplePage rows = scoped().simplePaginate(perPage, page);
        return new SimpleModelPage<>(rows, eagerLoaded(hydrate(rows.items())));
    }

    /**
     * Returns the first cursor page, of the model's page size.
     *
     * @return the page, never {@code null}
     */
    public CursorModelPage<T> cursorPaginate() {
        return cursorPaginate(model.getPerPage(), null);
    }

    /**
     * Returns the first cursor page.
     *
     * @param perPage the models per page, at least 1
     * @return the page, never {@code null}
     */
    public CursorModelPage<T> cursorPaginate(int perPage) {
        return cursorPaginate(perPage, null);
    }

    /**
     * Returns the cursor page a cursor points to, ordered by the qualified key when the query
     * has no order.
     *
     * @param perPage the models per page, at least 1
     * @param cursor the cursor from an earlier page, or {@code null} for the first page
     * @return the page, never {@code null}
     */
    public CursorModelPage<T> cursorPaginate(int perPage, Cursor cursor) {
        Builder ordered = scoped();
        if (ordered.getOrders().isEmpty() && ordered.getUnionOrders().isEmpty()) {
            ordered.orderBy(model.getQualifiedKeyName());
        }
        CursorPage rows = ordered.cursorPaginate(perPage, cursor);
        return new CursorModelPage<>(rows, eagerLoaded(hydrate(rows.items())));
    }
}

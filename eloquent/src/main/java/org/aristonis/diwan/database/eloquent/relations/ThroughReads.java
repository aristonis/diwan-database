package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.aristonis.diwan.database.eloquent.CursorModelPage;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelNotFoundException;
import org.aristonis.diwan.database.eloquent.ModelPage;
import org.aristonis.diwan.database.eloquent.MultipleRecordsFoundException;
import org.aristonis.diwan.database.eloquent.SimpleModelPage;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Cursor;
import org.aristonis.diwan.database.query.Order;
import org.aristonis.diwan.database.query.QueryCursor;

/**
 * The read methods of a through relation: finders, iterators and pages. Each method runs the
 * {@link EloquentBuilder} method of the same name on a copy of the relation's query, so a read never
 * changes the relation itself.
 *
 * <p>The copy comes from {@link #prepared}, with a {@link PivotReads.Select} step. Finders replace the
 * select, so the related columns are added only when nothing is selected. Iterators and pages append
 * the related columns to whatever is selected. Every copy carries the hidden through key and the row
 * hook that removes it.
 *
 * <p>Package-private: the through relation base is the only subclass, and it supplies {@link #prepared}.
 *
 * @param <R> the related model class
 * @param <S> the concrete relation class
 */
abstract class ThroughReads<R extends Model, S extends ThroughReads<R, S>> extends Relation<R, S> {

    /**
     * Builds the relation over the given query.
     *
     * @param query the related model's query, never {@code null}
     * @param parent the model the relation is read from, never {@code null}
     * @param relationName the relation's name, or {@code null} when none is known
     */
    ThroughReads(EloquentBuilder<R> query, Model parent, String relationName) {
        super(query, parent, relationName);
    }

    /**
     * Returns a copy of the constrained query with the select step applied and the row hook set. The
     * relation supplies the copy because the through key and the hook are its own.
     *
     * @param mode the select step, never {@code null}
     * @param columns the related columns passed to the read, never {@code null}; ignored when the
     *     relation already selects
     * @return the copy, never {@code null}
     */
    abstract EloquentBuilder<R> prepared(PivotReads.Select mode, String... columns);

    @Override
    final EloquentBuilder<R> readQuery() {
        return prepared(PivotReads.Select.REPLACE);
    }

    /**
     * Runs the lazy query with the given columns. The columns apply only when the relation's query
     * selects nothing; a select the relation already has is kept and the columns are ignored.
     *
     * @param columns the related columns to select, or none for every related column; no
     *     {@code null} element
     * @return the related models in query order, never {@code null}
     * @throws NullPointerException if {@code columns} is {@code null}
     */
    public final ModelCollection<R> get(String... columns) {
        return prepared(PivotReads.Select.REPLACE, columns).get();
    }

    /**
     * Finds a related model by its key, as {@link EloquentBuilder#find(Object)} does.
     *
     * @param id the key of the related model
     * @return the model, or empty when the parent has no such related model
     * @throws IllegalArgumentException if {@code id} is a {@link Collection}
     */
    public final Optional<R> find(Object id) {
        return prepared(PivotReads.Select.REPLACE).find(PivotRecords.findKey(id, relatedKey()));
    }

    /**
     * Finds the related models with the given keys, as {@link EloquentBuilder#find(Collection)} does.
     *
     * @param ids the keys, never {@code null}
     * @return the models found, in the database's order, never {@code null}
     */
    public final ModelCollection<R> find(Collection<?> ids) {
        return prepared(PivotReads.Select.REPLACE).find(PivotRecords.findKeys(ids, relatedKey()));
    }

    /**
     * Finds the related models with the given keys, as {@link EloquentBuilder#findMany(Collection)}
     * does. An empty collection runs no query.
     *
     * @param ids the keys, never {@code null}
     * @return the models found, in the database's order, never {@code null}
     */
    public final ModelCollection<R> findMany(Collection<?> ids) {
        return prepared(PivotReads.Select.REPLACE).findMany(PivotRecords.findKeys(ids, relatedKey()));
    }

    /**
     * Finds a related model by its key or throws, as {@link EloquentBuilder#findOrFail(Object)} does.
     *
     * @param id the key of the related model
     * @return the model, never {@code null}
     * @throws ModelNotFoundException if the far parent has no such related model
     */
    public final R findOrFail(Object id) {
        return prepared(PivotReads.Select.REPLACE).findOrFail(PivotRecords.findKey(id, relatedKey()));
    }

    /**
     * Finds the related models with the given keys or throws, as
     * {@link EloquentBuilder#findOrFail(Collection)} does.
     *
     * @param ids the keys, never {@code null}
     * @return the models, never {@code null}
     * @throws ModelNotFoundException if any key is not found, naming the missing keys
     */
    public final ModelCollection<R> findOrFail(Collection<?> ids) {
        return prepared(PivotReads.Select.REPLACE).findOrFail(PivotRecords.findKeys(ids, relatedKey()));
    }

    /**
     * Finds a related model by its key, or returns the supplier's result, as
     * {@link EloquentBuilder#findOr(Object, Supplier)} does.
     *
     * @param id the key of the related model
     * @param fallback gives the result when there is none, never {@code null}
     * @return the model, or the fallback's result
     */
    public final R findOr(Object id, Supplier<R> fallback) {
        return prepared(PivotReads.Select.REPLACE).findOr(PivotRecords.findKey(id, relatedKey()), fallback);
    }

    /**
     * Returns the first related model, as {@link EloquentBuilder#first()} does.
     *
     * @return the model, or empty when the parent has none
     */
    public final Optional<R> first() {
        return prepared(PivotReads.Select.REPLACE).first();
    }

    /**
     * Returns the first related model or throws, as {@link EloquentBuilder#firstOrFail()} does.
     *
     * @return the model, never {@code null}
     * @throws ModelNotFoundException if the far parent has none
     */
    public final R firstOrFail() {
        return prepared(PivotReads.Select.REPLACE).firstOrFail();
    }

    /**
     * Returns the first related model, or the supplier's result, as
     * {@link EloquentBuilder#firstOr(Supplier)} does.
     *
     * @param fallback gives the result when the parent has none, never {@code null}
     * @return the model, or the fallback's result
     */
    public final R firstOr(Supplier<R> fallback) {
        return prepared(PivotReads.Select.REPLACE).firstOr(fallback);
    }

    /**
     * Returns the first related model whose column equals a value, as
     * {@link EloquentBuilder#firstWhere(String, Object)} does.
     *
     * @param column the column, never {@code null}; name a through-table column with its table to keep it
     *     apart from a related column of the same name
     * @param value the value
     * @return the model, or empty when none matches
     */
    public final Optional<R> firstWhere(String column, Object value) {
        return prepared(PivotReads.Select.REPLACE).firstWhere(column, value);
    }

    /**
     * Returns the first related model whose column compares to a value, as
     * {@link EloquentBuilder#firstWhere(String, String, Object)} does.
     *
     * @param column the column, never {@code null}; qualified as in {@link #firstWhere(String, Object)}
     * @param operator the operator, never {@code null}
     * @param value the value
     * @return the model, or empty when none matches
     */
    public final Optional<R> firstWhere(String column, String operator, Object value) {
        return prepared(PivotReads.Select.REPLACE).firstWhere(column, operator, value);
    }

    /**
     * Returns the only related model, reading at most two rows, as {@link EloquentBuilder#sole()} does.
     *
     * @return the model, never {@code null}
     * @throws ModelNotFoundException if the far parent has none
     * @throws MultipleRecordsFoundException if the far parent has more than one
     */
    public final R sole() {
        return prepared(PivotReads.Select.REPLACE).sole();
    }

    /**
     * Reads the related models in chunks by offset, ordered by the related key when the relation has no
     * order, as {@link EloquentBuilder#chunk(int, Predicate)} does.
     *
     * @param count the models per chunk, at least 1
     * @param callback takes each chunk; returning {@code false} stops, never {@code null}
     * @return {@code false} when the callback stopped early
     */
    public final boolean chunk(int count, Predicate<ModelCollection<R>> callback) {
        return prepared(PivotReads.Select.APPEND).chunk(count, callback);
    }

    /**
     * Reads the related models in chunks by the related key, as
     * {@link EloquentBuilder#chunkById(int, Predicate)} does with the qualified related key.
     *
     * @param count the models per chunk, at least 1
     * @param callback takes each chunk; returning {@code false} stops, never {@code null}
     * @return {@code false} when the callback stopped early
     */
    public final boolean chunkById(int count, Predicate<ModelCollection<R>> callback) {
        String key = relatedKey();
        return chunkById(count, callback, getRelated().qualifyColumn(key), key);
    }

    /**
     * Reads the related models in chunks by a column, as
     * {@link EloquentBuilder#chunkById(int, Predicate, String, String)} does.
     *
     * @param count the models per chunk, at least 1
     * @param callback takes each chunk; returning {@code false} stops, never {@code null}
     * @param column the column to page by, never {@code null}
     * @param alias the column's name in the rows, never {@code null}
     * @return {@code false} when the callback stopped early
     */
    public final boolean chunkById(int count, Predicate<ModelCollection<R>> callback, String column,
            String alias) {
        return prepared(PivotReads.Select.APPEND).chunkById(count, callback, column, alias);
    }

    /**
     * Visits each related model, reading in chunks of 1000, as {@link EloquentBuilder#each(Predicate)}
     * does.
     *
     * @param callback takes each model; returning {@code false} stops, never {@code null}
     * @return {@code false} when the callback stopped early
     */
    public final boolean each(Predicate<R> callback) {
        return prepared(PivotReads.Select.APPEND).each(callback);
    }

    /**
     * Visits each related model, reading in chunks, as {@link EloquentBuilder#each(Predicate, int)} does.
     *
     * @param callback takes each model; returning {@code false} stops, never {@code null}
     * @param count the models per chunk, at least 1
     * @return {@code false} when the callback stopped early
     */
    public final boolean each(Predicate<R> callback, int count) {
        return prepared(PivotReads.Select.APPEND).each(callback, count);
    }

    /**
     * Returns the related models as a lazy sequence read in chunks of 1000, as
     * {@link EloquentBuilder#lazy()} does.
     *
     * @return the models, read as they are iterated, never {@code null}
     */
    public final Iterable<R> lazy() {
        return prepared(PivotReads.Select.APPEND).lazy();
    }

    /**
     * Returns the related models as a lazy sequence read in chunks, as {@link EloquentBuilder#lazy(int)}
     * does.
     *
     * @param count the rows per read, at least 1
     * @return the models, read as they are iterated, never {@code null}
     */
    public final Iterable<R> lazy(int count) {
        return prepared(PivotReads.Select.APPEND).lazy(count);
    }

    /**
     * Returns the related models as a lazy sequence read in chunks by the related key, as
     * {@link EloquentBuilder#lazyById(int)} does with the qualified related key.
     *
     * @param count the rows per read, at least 1
     * @return the models, read as they are iterated, never {@code null}
     */
    public final Iterable<R> lazyById(int count) {
        String key = relatedKey();
        return lazyById(count, getRelated().qualifyColumn(key), key);
    }

    /**
     * Returns the related models as a lazy sequence read in chunks by a column, as
     * {@link EloquentBuilder#lazyById(int, String, String)} does.
     *
     * @param count the rows per read, at least 1
     * @param column the column to page by, never {@code null}
     * @param alias the column's name in the rows, never {@code null}
     * @return the models, read as they are iterated, never {@code null}
     */
    public final Iterable<R> lazyById(int count, String column, String alias) {
        return prepared(PivotReads.Select.APPEND).lazyById(count, column, alias);
    }

    /**
     * Opens one cursor over the related models, as {@link EloquentBuilder#cursor()} does. Close the
     * cursor, best with try-with-resources. The cursor holds its session while open, so use
     * {@link EloquentBuilder#lazy(int)} for a slow loop or one that writes.
     *
     * @return the open cursor, never {@code null}
     * @throws IllegalStateException if eager loads are set on the relation
     */
    public final QueryCursor<R> cursor() {
        return prepared(PivotReads.Select.APPEND).cursor();
    }

    /**
     * Returns the first page of related models, of the model's page size, with the total, as
     * {@link EloquentBuilder#paginate()} does.
     *
     * @return the page, never {@code null}
     */
    public final ModelPage<R> paginate() {
        return prepared(PivotReads.Select.APPEND).paginate();
    }

    /**
     * Returns the first page of related models with the total, as {@link EloquentBuilder#paginate(int)}
     * does.
     *
     * @param perPage the models per page, at least 1
     * @return the page, never {@code null}
     */
    public final ModelPage<R> paginate(int perPage) {
        return prepared(PivotReads.Select.APPEND).paginate(perPage);
    }

    /**
     * Returns one page of related models with the total, as {@link EloquentBuilder#paginate(int, int)}
     * does. The total count drops the select list and keeps the joins and the conditions.
     *
     * @param perPage the models per page, at least 1
     * @param page the page number, from 1
     * @return the page, never {@code null}
     */
    public final ModelPage<R> paginate(int perPage, int page) {
        return prepared(PivotReads.Select.APPEND).paginate(perPage, page);
    }

    /**
     * Returns the first page of related models without a total, of the model's page size, as
     * {@link EloquentBuilder#simplePaginate()} does.
     *
     * @return the page, never {@code null}
     */
    public final SimpleModelPage<R> simplePaginate() {
        return prepared(PivotReads.Select.APPEND).simplePaginate();
    }

    /**
     * Returns the first page of related models without a total, as
     * {@link EloquentBuilder#simplePaginate(int)} does.
     *
     * @param perPage the models per page, at least 1
     * @return the page, never {@code null}
     */
    public final SimpleModelPage<R> simplePaginate(int perPage) {
        return prepared(PivotReads.Select.APPEND).simplePaginate(perPage);
    }

    /**
     * Returns one page of related models without a total, as
     * {@link EloquentBuilder#simplePaginate(int, int)} does.
     *
     * @param perPage the models per page, at least 1
     * @param page the page number, from 1
     * @return the page, never {@code null}
     */
    public final SimpleModelPage<R> simplePaginate(int perPage, int page) {
        return prepared(PivotReads.Select.APPEND).simplePaginate(perPage, page);
    }

    /**
     * Returns the first cursor page of related models, of the model's page size, as
     * {@link EloquentBuilder#cursorPaginate()} does.
     *
     * @return the page, never {@code null}
     * @throws IllegalStateException if the relation orders by a column not on the related table
     */
    public final CursorModelPage<R> cursorPaginate() {
        return cursorQuery().cursorPaginate();
    }

    /**
     * Returns the first cursor page of related models, as {@link EloquentBuilder#cursorPaginate(int)}
     * does.
     *
     * @param perPage the models per page, at least 1
     * @return the page, never {@code null}
     * @throws IllegalStateException if the relation orders by a column not on the related table
     */
    public final CursorModelPage<R> cursorPaginate(int perPage) {
        return cursorQuery().cursorPaginate(perPage);
    }

    /**
     * Returns the cursor page a cursor points to, as {@link EloquentBuilder#cursorPaginate(int, Cursor)}
     * does.
     *
     * @param perPage the models per page, at least 1
     * @param cursor the cursor from an earlier page, or {@code null} for the first page
     * @return the page, never {@code null}
     * @throws IllegalStateException if the relation orders by a column not on the related table
     */
    public final CursorModelPage<R> cursorPaginate(int perPage, Cursor cursor) {
        return cursorQuery().cursorPaginate(perPage, cursor);
    }

    /**
     * Finds the only related model with the given key, reading at most two rows, so a key that joins to
     * several through rows is refused.
     *
     * @param id the key of the related model
     * @return the model, never {@code null}
     * @throws ModelNotFoundException if the far parent has no such related model
     * @throws MultipleRecordsFoundException if the key matches more than one row
     * @throws IllegalArgumentException if {@code id} is a {@link Collection}
     */
    public final R findSole(Object id) {
        if (id instanceof Collection) {
            throw new IllegalArgumentException("findSole() takes one key");
        }
        Object key = PivotRecords.findKey(id, relatedKey());
        return prepared(PivotReads.Select.REPLACE).where(getRelated().qualifyColumn(relatedKey()), key).sole();
    }

    private String relatedKey() {
        return getRelated().getKeyName();
    }

    // The cursor reads its keyset value from the related row, so an order on another table would page on
    // the wrong value. The refusal runs before any query.
    private EloquentBuilder<R> cursorQuery() {
        EloquentBuilder<R> query = prepared(PivotReads.Select.APPEND);
        refuseForeignOrders(query);
        return query;
    }

    private void refuseForeignOrders(EloquentBuilder<R> query) {
        String prefix = getRelated().getTable().toLowerCase(Locale.ROOT) + ".";
        Builder base = query.toBase();
        refuseOrders(base.getOrders(), prefix);
        refuseOrders(base.getUnionOrders(), prefix);
    }

    private void refuseOrders(List<Order> orders, String prefix) {
        for (Order order : orders) {
            if (!(order instanceof Order.Column)) {
                continue;
            }
            String column = ((Order.Column) order).column().replace("\"", "");
            if (!column.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                throw refusal(column);
            }
        }
    }

    private IllegalStateException refusal(String column) {
        String name = getRelationName() != null ? getRelationName() : getRelated().getTable();
        return new IllegalStateException("cursorPaginate cannot order relation [" + name + "] of ["
                + getParent().getClass().getName() + "] by the column [" + column + "]: the cursor reads "
                + "its value from the related row; qualify the column with the related table ["
                + getRelated().getTable() + "]");
    }
}

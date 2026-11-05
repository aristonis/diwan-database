package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.EloquentAccess;
import org.aristonis.diwan.database.query.QueryGrammar;

/**
 * Loads relation aggregates onto models already in memory: Laravel's
 * {@code Collection::loadAggregate}.
 *
 * <p>One key-only query of the models' class selects the key and the aggregate subselects, filtered
 * by the models' keys. The subselects are added once, so a constraint runs once, and the query is
 * copied per key chunk: integer keys are written into the SQL up to the grammar's inline size, other
 * keys are bound in chunks of the grammar's eager size less the constraint bindings. The rows are
 * read raw, so no throwaway model is hydrated, {@code retrieved()} does not run and no eager load
 * fires; Laravel copies the same values from its throwaway models.
 *
 * <p>Each model whose row came back gets the aggregate columns through {@code forceFill}, those
 * columns synced as original so the model is not dirty, then the query's cast overlay. A model
 * with a {@code null} key or whose row is gone is skipped; Laravel fails on it. The names
 * are checked before the keys, so models that all have a {@code null} key still reject an unknown
 * name, with no query built and no connection needed.
 *
 * <p>Stateless; safe to use from any thread. The models it is given are not.
 */
final class AggregateLoader {

    private AggregateLoader() {
    }

    /**
     * Loads the aggregates of the given relations onto models of one class.
     *
     * @param models the models, never {@code null}; empty does nothing and checks no name
     * @param relations the constraints keyed by raw name, {@code as} alias included, never
     *     {@code null}
     * @param column the aggregated column: a {@code String} ({@code "*"} for count and exists) or a
     *     core {@code Expression}, never {@code null}
     * @param function the aggregate function, or {@code null} for one related row's column
     * @throws NullPointerException if {@code models}, a model, {@code relations} or {@code column}
     *     is {@code null}
     * @throws IllegalArgumentException if the models are not all of one class, the function is not
     *     allowed, an inlined key is not an integer, or the constraint bindings leave no room for a
     *     key; no query has run
     * @throws RelationNotFoundException if a name is unknown or is a dot path, even when no model
     *     has a key; no query has run
     * @throws IllegalStateException if a constraint uses a clause the subselect cannot carry
     */
    static void load(List<? extends Model> models, Map<String, Consumer<EloquentBuilder<?>>> relations,
            Object column, String function) {
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(relations, "relations");
        Objects.requireNonNull(column, "column");
        if (models.isEmpty()) {
            return;
        }
        Model first = requireOneClass(models);
        // Checked before the keys and without a query, so a bad name fails even when no model
        // has a key, and an unsaved model with no connection runs nothing.
        RelationAggregates.check(first, relations, column, function);
        List<Object> keys = EagerKeys.collect(models, Model::getKey);
        if (keys.isEmpty()) {
            return;
        }
        EloquentAccess access = Access.eloquent();
        String keyName = first.getKeyName();
        EloquentBuilder<Model> outer = access.newQueryOn(first).withoutGlobalScopes().select(keyName);
        RelationAggregates.add(outer, relations, column, function);
        String names = String.join(", ", relations.keySet());
        boolean isInline = Relation.isIntegerKey(first, keyName);
        int size = chunkSize(outer, isInline, names);
        String qualified = first.qualifyColumn(keyName);
        List<?> filterKeys = isInline ? exactLongs(keys, first, names) : keys;
        Map<Object, Map<String, Object>> rows = new HashMap<>();
        for (List<?> chunk : EagerKeys.chunks(filterKeys, size)) {
            // A copy per chunk: the subselects and their constraints are not built again.
            EloquentBuilder<Model> query = access.copy(outer);
            if (isInline) {
                query.query(base -> base.whereIntegerInRaw(qualified, chunk));
            } else {
                query.whereIn(qualified, chunk);
            }
            for (Map<String, Object> row : query.toBase().get()) {
                rows.put(EagerKeys.normalise(row.get(keyName)), row);
            }
        }
        copyBack(models, rows, keyName, access.castsToMerge(outer));
    }

    private static Model requireOneClass(List<? extends Model> models) {
        Model first = Objects.requireNonNull(models.get(0), "model");
        Class<?> type = first.getClass();
        for (Model model : models) {
            Objects.requireNonNull(model, "model");
            if (model.getClass() != type) {
                throw new IllegalArgumentException("Cannot load aggregates onto models of different classes: ["
                        + type.getName() + "] and [" + model.getClass().getName() + "]");
            }
        }
        return first;
    }

    private static int chunkSize(EloquentBuilder<Model> outer, boolean isInline, String names) {
        QueryGrammar grammar = outer.toBase().getGrammar();
        if (isInline) {
            return grammar.inlineKeyChunkSize();
        }
        int size = grammar.eagerKeyChunkSize() - outer.getBindings().size();
        if (size < 1) {
            throw new IllegalArgumentException("The constraint bindings of [" + names
                    + "] leave no room for keys in one query");
        }
        return size;
    }

    private static List<Long> exactLongs(List<Object> keys, Model first, String names) {
        List<Long> longs = new ArrayList<>(keys.size());
        for (Object key : keys) {
            longs.add(EagerKeys.exactLong(key, first.getClass(), names));
        }
        return longs;
    }

    private static void copyBack(List<? extends Model> models, Map<Object, Map<String, Object>> rows,
            String keyName, Map<String, Cast<?>> overlay) {
        for (Model model : models) {
            Object key = EagerKeys.normalise(model.getKey());
            Map<String, Object> row = key == null ? null : rows.get(key);
            if (row == null) {
                continue;
            }
            Map<String, Object> extra = new LinkedHashMap<>(row);
            extra.remove(keyName);
            model.forceFill(extra);
            model.syncOriginalAttributes(extra.keySet().toArray(new String[0]));
            if (!overlay.isEmpty()) {
                model.mergeCasts(overlay);
            }
        }
    }
}

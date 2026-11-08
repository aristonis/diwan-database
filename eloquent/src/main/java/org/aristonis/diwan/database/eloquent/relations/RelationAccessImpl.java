package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.RelationAccess;
import org.aristonis.diwan.database.eloquent.internal.VisitPath;

/**
 * The relation package's side of the internal bridge: eager loading, existence queries,
 * aggregate subselects, aggregates on loaded models, owner touches and name resolution for the
 * model package. Registered once by {@code Relation}'s static initialiser.
 *
 * <p>Keeps no state; safe to call from any thread.
 */
final class RelationAccessImpl implements RelationAccess {

    /**
     * Creates the bridge; {@code Relation}'s static initialiser is the only caller outside tests.
     */
    RelationAccessImpl() {
    }

    @Override
    public void load(List<? extends Model> models, Map<String, Consumer<EloquentBuilder<?>>> relations) {
        EagerLoader.load(models, relations);
    }

    @Override
    public void loadMissing(List<? extends Model> models, Map<String, Consumer<EloquentBuilder<?>>> relations) {
        EagerLoader.loadMissing(models, relations);
    }

    @Override
    public void addExistence(EloquentBuilder<?> outer, String relation, String operator, long count, boolean or,
            Consumer<EloquentBuilder<?>> constraint) {
        RelationExistence.add(Objects.requireNonNull(outer, "outer"), Objects.requireNonNull(relation, "relation"),
                Objects.requireNonNull(operator, "operator"), count, or, constraint);
    }

    @Override
    public void addMorphExistence(EloquentBuilder<?> outer, String relation, List<Object> types, String operator,
            long count, boolean or, BiConsumer<EloquentBuilder<?>, Class<? extends Model>> constraint) {
        MorphExistence.hasMorph(outer, relation, types, operator, count, or, constraint);
    }

    @Override
    public void whereMorphedTo(EloquentBuilder<?> outer, String relation, Object target, boolean not, boolean or) {
        MorphExistence.whereMorphedTo(outer, relation, target, not, or);
    }

    @Override
    public void whereBelongsTo(EloquentBuilder<?> builder, List<? extends Model> related, String relation,
            boolean or) {
        WhereBelongsTo.add(Objects.requireNonNull(builder, "builder"), Objects.requireNonNull(related, "related"),
                relation, or);
    }

    @Override
    public Consumer<EloquentBuilder<?>> morphEager(Consumer<MorphEagerOptions> options,
            Consumer<EloquentBuilder<?>> plain) {
        return MorphEagerConstraint.of(Objects.requireNonNull(options, "options"), plain);
    }

    @Override
    public Consumer<EloquentBuilder<?>> combineEager(Consumer<EloquentBuilder<?>> a,
            Consumer<EloquentBuilder<?>> b) {
        return MorphEagerConstraint.combine(a, b);
    }

    @Override
    public void loadMorph(Collection<? extends Model> models, String relation,
            Map<Class<? extends Model>, List<String>> relations, boolean isCount) {
        MorphLoads.loadMorph(models, relation, relations, isCount);
    }

    @Override
    public void addAggregate(EloquentBuilder<?> outer, Map<String, Consumer<EloquentBuilder<?>>> relations,
            Object column, String function) {
        RelationAggregates.add(outer, relations, column, function);
    }

    @Override
    public void loadAggregate(List<? extends Model> models, Map<String, Consumer<EloquentBuilder<?>>> relations,
            Object column, String function) {
        AggregateLoader.load(models, relations, column, function);
    }

    @Override
    public void touchOwners(Model model, VisitPath path) {
        RelationTouches.touchOwners(model, path);
    }

    @Override
    public Relation<?, ?> resolve(Model model, String name) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(name, "name");
        return RelationRegistry.of(model).resolve(name, model);
    }

    @Override
    public String aliasOf(Class<? extends Model> type) {
        return MorphMap.aliasOf(type);
    }

    @Override
    public Class<? extends Model> resolveMorph(String type, ClassLoader loader) {
        return MorphMap.resolve(type, loader);
    }

    @Override
    public <R extends Model> BelongsToMany<R> newBelongsToMany(EloquentBuilder<R> query, Model parent,
            String table, String foreignPivotKey, String relatedPivotKey, String parentKey,
            String relatedKey, String relation) {
        return new BelongsToMany<>(query, parent, table, foreignPivotKey, relatedPivotKey,
                parentKey, relatedKey, relation);
    }

    @Override
    public <R extends Model> MorphToMany<R> newMorphToMany(EloquentBuilder<R> query, Model parent,
            String name, String table, String foreignPivotKey, String relatedPivotKey,
            String parentKey, String relatedKey, String relation, boolean inverse) {
        String pivotTable = table != null && !table.isEmpty() ? table : MorphToMany.defaultTable(name);
        return new MorphToMany<>(query, parent, name, pivotTable, foreignPivotKey, relatedPivotKey,
                parentKey, relatedKey, relation, inverse);
    }
}

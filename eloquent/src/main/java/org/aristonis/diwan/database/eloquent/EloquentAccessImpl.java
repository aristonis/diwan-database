package org.aristonis.diwan.database.eloquent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;
import org.aristonis.diwan.database.eloquent.internal.EloquentAccess;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * Gives the relation package the model package's package-private members, without reflection.
 *
 * <p>Registered once by {@link ModelQuery}'s static initialiser. Keeps no state and is safe to
 * call from any thread.
 */
final class EloquentAccessImpl implements EloquentAccess {

    /**
     * Creates the bridge; only {@link ModelQuery} does.
     */
    EloquentAccessImpl() {
    }

    @Override
    public <T extends Model> ModelCollection<T> newCollection(EloquentBuilder<T> source, List<T> models) {
        Objects.requireNonNull(source, "source");
        return new ModelCollection<>(Objects.requireNonNull(models, "models"), source.source);
    }

    @Override
    public <T extends Model> EloquentBuilder<T> copy(EloquentBuilder<T> builder) {
        return Objects.requireNonNull(builder, "builder").copy();
    }

    @Override
    public <T extends Model> EloquentBuilder<T> withoutEagerLoads(EloquentBuilder<T> builder) {
        EloquentBuilder<T> copied = Objects.requireNonNull(builder, "builder").copy();
        copied.clearEagerLoads();
        return copied;
    }

    @Override
    public void shareAliases(EloquentBuilder<?> from, EloquentBuilder<?> to) {
        AliasCounter counter = Objects.requireNonNull(from, "from").aliases();
        Objects.requireNonNull(to, "to").useAliases(counter);
    }

    @Override
    public AliasCounter aliases(EloquentBuilder<?> builder) {
        return Objects.requireNonNull(builder, "builder").aliases();
    }

    @Override
    public void useTableAlias(Model model, String alias) {
        Objects.requireNonNull(model, "model").useTableAlias(Objects.requireNonNull(alias, "alias"));
    }

    @Override
    public boolean isLazyLoadingPrevented(Model model) {
        return Objects.requireNonNull(model, "model").isLazyLoadingPrevented();
    }

    @Override
    public Map<String, Consumer<EloquentBuilder<?>>> parseWith(String... relations) {
        return WithParser.parse(relations);
    }

    @Override
    public Model newInstanceLike(Model model) {
        return Objects.requireNonNull(model, "model").newInstanceLike();
    }

    @Override
    public AtomicReference<Object> relationRegistrySlot(Model model) {
        return entryOf(model).relationRegistry();
    }

    @Override
    public RelationMap<?> relationMap(Model model) {
        return Objects.requireNonNull(Objects.requireNonNull(model, "model").relations(), "relations");
    }

    @Override
    public <T extends Model> EloquentBuilder<T> newQueryOn(T model) {
        // Safe: the registry keys each entry by its own model class.
        @SuppressWarnings("unchecked")
        ModelQuery<T> entry = (ModelQuery<T>) entryOf(model);
        if (model.isBound()) {
            entry = entry.on(model.getQueryFactory());
        }
        return new EloquentBuilder<>(entry, entry.newModel());
    }

    @Override
    public Set<String> touches(Model model) {
        return Objects.requireNonNull(Objects.requireNonNull(model, "model").touches(), "touches");
    }

    @Override
    public AtomicReference<Object> touchesSlot(Model model) {
        return entryOf(model).touchesSlot();
    }

    @Override
    public void fireSaved(Model model) {
        Objects.requireNonNull(model, "model").saved();
    }

    @Override
    public QueryFactory swapFactory(Model model, QueryFactory next) {
        return Objects.requireNonNull(model, "model").swapFactory(next);
    }

    @Override
    public Map<String, Cast<?>> castsToMerge(EloquentBuilder<?> builder) {
        return Objects.requireNonNull(builder, "builder").castsToMerge();
    }

    @Override
    public <T extends Model> void useRowHook(EloquentBuilder<T> builder, Consumer<? super T> hook) {
        Objects.requireNonNull(builder, "builder").useRowHook(hook);
    }

    @Override
    public Map<String, Object> takeAttributes(Model model, String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return Objects.requireNonNull(model, "model").takeAttributesWithPrefix(prefix);
    }

    @Override
    public void markExisting(Model model) {
        Objects.requireNonNull(model, "model").markExisting();
    }

    @Override
    public <M extends Model> M newRegistered(Class<M> type, Model connectionOf) {
        ModelQuery<?> entry = ModelQuery.registeredOrLoad(Objects.requireNonNull(type, "type"));
        Model model = entry.newModel();
        // Laravel's newRelatedInstance: a model with no connection of its own runs on the parent's.
        // A registered entry is never bound, so its connection name decides.
        if (!model.isBound() && model.getConnectionName() == null && connectionOf != null && connectionOf.isBound()) {
            model = entry.on(connectionOf.getQueryFactory()).newModel();
        }
        return type.cast(model);
    }

    private static ModelQuery<?> entryOf(Model model) {
        Class<?> type = Objects.requireNonNull(model, "model").getClass();
        ModelQuery<?> entry = ModelQuery.registered(type);
        if (entry == null) {
            throw new IllegalStateException("Model [" + type.getName()
                    + "] has no ModelQuery.of(...) entry to create instances with");
        }
        return entry;
    }
}

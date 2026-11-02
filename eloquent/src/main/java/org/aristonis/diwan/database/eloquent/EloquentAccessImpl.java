package org.aristonis.diwan.database.eloquent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;
import org.aristonis.diwan.database.eloquent.internal.EloquentAccess;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;

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
        Class<?> type = Objects.requireNonNull(model, "model").getClass();
        ModelQuery<?> entry = ModelQuery.registered(type);
        if (entry == null) {
            throw new IllegalStateException("Model [" + type.getName()
                    + "] has no ModelQuery.of(...) entry to create instances with");
        }
        return entry.relationRegistry();
    }

    @Override
    public RelationMap<?> relationMap(Model model) {
        return Objects.requireNonNull(Objects.requireNonNull(model, "model").relations(), "relations");
    }
}

package org.aristonis.diwan.database.eloquent.relations;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.RelationAccess;

/**
 * The relation package's side of the internal bridge: eager loading, existence queries and
 * name resolution for the model package. Registered once by {@code Relation}'s static
 * initialiser.
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
    public Relation<?, ?> resolve(Model model, String name) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(name, "name");
        return RelationRegistry.of(model).resolve(name, model);
    }
}

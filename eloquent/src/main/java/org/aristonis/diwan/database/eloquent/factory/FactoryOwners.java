package org.aristonis.diwan.database.eloquent.factory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.MorphTo;
import org.aristonis.diwan.database.eloquent.relations.Relation;

/**
 * The for flow: finds the parent of a model and returns the key column, and for a morph-to
 * relation the type column, that point at it.
 */
final class FactoryOwners {

    private FactoryOwners() {
    }

    /**
     * Returns the columns a for link puts on the child.
     *
     * @param link the link, never {@code null}
     * @param spec the settings of the child factory, never {@code null}
     * @param run the frame of the run, never {@code null}
     * @return a new map of column names to values, never {@code null}
     * @throws IllegalArgumentException if the relation is not a belongs-to or morph-to relation
     * @throws IllegalStateException if a model parent is not saved, a factory parent is needed in a
     *     database-free run, or the nesting is too deep
     */
    static Map<String, Object> columns(ForLink link, FactorySpec<?> spec, FactoryRun run) {
        Model probe = spec.effectiveQuery().newModel();
        Relation<?, ?> relation = probe.relation(link.relation());
        if (!(relation instanceof MorphTo) && !(relation instanceof BelongsTo)) {
            throw new IllegalArgumentException("forParent() needs a belongs-to or morph-to relation, but ["
                    + link.relation() + "] of model [" + probe.getClass().getName() + "] is a ["
                    + relation.getClass().getName() + "]");
        }
        Model owner = run.owner(link, () -> resolveOwner(link, spec, run));
        if (relation instanceof MorphTo) {
            MorphTo<?> morphTo = (MorphTo<?>) relation;
            Object ownerKey = morphTo.getOwnerKeyName().map(owner::getAttribute).orElseGet(owner::getKey);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(morphTo.getForeignKeyName(), ownerKey);
            out.put(morphTo.getMorphType(), owner.getMorphClass());
            return out;
        }
        BelongsTo<?> belongsTo = (BelongsTo<?>) relation;
        return Collections.singletonMap(belongsTo.getForeignKeyName(),
                owner.getAttribute(belongsTo.getOwnerKeyName()));
    }

    private static Model resolveOwner(ForLink link, FactorySpec<?> spec, FactoryRun run) {
        if (link.model() != null) {
            return savedModel(link.model());
        }
        FactorySpec<?> parentSpec = link.factory().bound();
        Class<?> parentClass = parentSpec.query().getModelClass();
        if (run.isDatabaseFree()) {
            throw new IllegalStateException("raw() of [" + spec.query().getModelClass().getName()
                    + "] cannot create a [" + parentClass.getName() + "]; use make() or create()");
        }
        Model recycled = run.recycled(parentClass);
        if (recycled != null) {
            return recycled;
        }
        return createParent(parentSpec.withConnectionIfAbsent(spec.connection()), run);
    }

    private static Model savedModel(Model model) {
        if (!model.exists()) {
            throw new IllegalStateException("The model [" + model.getClass().getName()
                    + "] given to forParent() is not saved");
        }
        return model;
    }

    private static <M extends Model> Model createParent(FactorySpec<M> parentSpec, FactoryRun run) {
        String className = parentSpec.query().getModelClass().getName();
        return FactoryBuild.createOne(parentSpec, run.nested(null, className), 0);
    }
}

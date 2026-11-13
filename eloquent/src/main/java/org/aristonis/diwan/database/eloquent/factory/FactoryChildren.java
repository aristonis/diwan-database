package org.aristonis.diwan.database.eloquent.factory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.BelongsToMany;
import org.aristonis.diwan.database.eloquent.relations.HasOneOrMany;
import org.aristonis.diwan.database.eloquent.relations.MorphOneOrMany;
import org.aristonis.diwan.database.eloquent.relations.MorphTo;
import org.aristonis.diwan.database.eloquent.relations.Relation;

/**
 * The has and has-attached flows: creates the children of a saved model, and checks every link
 * before any row is written.
 */
final class FactoryChildren {

    private FactoryChildren() {
    }

    /**
     * Creates the children and attached models of every has link of a saved model, in the order the
     * links were added.
     *
     * @param saved the model that was just saved, never {@code null}
     * @param spec the settings of the factory that created it, never {@code null}
     * @param frame the frame the model was created in, never {@code null}
     * @throws IllegalArgumentException if a relation is not of the kind the link needs
     * @throws IllegalStateException if the parent has no value for the key a child needs, or the
     *     nesting is too deep
     */
    static void createFor(Model saved, FactorySpec<?> spec, FactoryRun frame) {
        for (HasLink link : spec.relations().hasLinks()) {
            Relation<?, ?> relation = saved.relation(link.relation());
            if (link.isAttached()) {
                attach(saved, relation, link, spec, frame);
            } else {
                createChildren(saved, relation, link, spec, frame);
            }
        }
    }

    /**
     * Checks every link of a factory and of the factories it links to, before any row is written.
     *
     * <p>For links are always checked, and so are the links of the parent factories they name. Has
     * and has-attached links are checked only when {@code hasToo} is set. A factory value inside an
     * attribute map is not inspected.
     *
     * @param spec the settings of the factory the call was made on, never {@code null}
     * @param hasToo whether has and has-attached links run in this call
     * @throws IllegalArgumentException if a relation is not of the kind the link needs
     */
    static void validate(FactorySpec<?> spec, boolean hasToo) {
        if (spec.relations().forLinks().isEmpty() && (!hasToo || spec.relations().hasLinks().isEmpty())) {
            return;
        }
        Model probe = spec.effectiveQuery().newModel();
        for (ForLink link : spec.relations().forLinks()) {
            requireForKind(probe, link.relation());
            if (link.factory() != null) {
                // A parent is always created through createOne, so its own has links run in every call.
                validate(link.factory().bound().withConnectionIfAbsent(spec.connection()), true);
            }
        }
        if (!hasToo) {
            return;
        }
        for (HasLink link : spec.relations().hasLinks()) {
            Relation<?, ?> relation = probe.relation(link.relation());
            if (link.isAttached()) {
                requireAttachKind(relation, link.relation(), probe);
            } else {
                requireHasKind(relation, link.relation(), probe);
            }
            if (link.factory() != null) {
                validate(link.factory().bound().withConnectionIfAbsent(spec.connection()), hasToo);
            }
        }
    }

    private static void requireForKind(Model probe, String name) {
        Relation<?, ?> relation = probe.relation(name);
        if (!(relation instanceof BelongsTo) && !(relation instanceof MorphTo)) {
            throw new IllegalArgumentException("forParent() needs a belongs-to or morph-to relation, but [" + name
                    + "] of model [" + probe.getClass().getName() + "] is a [" + relation.getClass().getName()
                    + "]");
        }
    }

    private static void requireHasKind(Relation<?, ?> relation, String name, Model model) {
        if (!(relation instanceof HasOneOrMany)) {
            throw new IllegalArgumentException("has() cannot fill relation [" + name + "] of model ["
                    + model.getClass().getName() + "]: it is a [" + relation.getClass().getName() + "]");
        }
    }

    private static void requireAttachKind(Relation<?, ?> relation, String name, Model model) {
        if (!(relation instanceof BelongsToMany)) {
            throw new IllegalArgumentException("hasAttached() needs a many-to-many relation, but [" + name
                    + "] of model [" + model.getClass().getName() + "] is a [" + relation.getClass().getName()
                    + "]");
        }
    }

    private static void createChildren(
            Model saved, Relation<?, ?> relation, HasLink link, FactorySpec<?> spec, FactoryRun frame) {
        requireHasKind(relation, link.relation(), saved);
        HasOneOrMany<?, ?> hasMany = (HasOneOrMany<?, ?>) relation;
        Object parentKey = hasMany.getParentKey();
        if (parentKey == null) {
            String childClass = link.factory().bound().query().getModelClass().getName();
            throw new IllegalStateException("Cannot create [" + childClass + "] under ["
                    + saved.getClass().getName() + "]: the parent has no value for ["
                    + hasMany.getLocalKeyName() + "]");
        }
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put(hasMany.getForeignKeyName(), parentKey);
        if (relation instanceof MorphOneOrMany) {
            MorphOneOrMany<?, ?> morph = (MorphOneOrMany<?, ?>) relation;
            columns.put(morph.getMorphTypeName(), morph.getMorphClass());
        }
        Map<String, Object> fixed = Collections.unmodifiableMap(columns);
        createBatch(link.factory().bound().withConnectionIfAbsent(spec.connection())
                .withState((s, soFar, i, p) -> fixed), saved, frame);
    }

    private static <C extends Model> void createBatch(FactorySpec<C> childSpec, Model parent, FactoryRun frame) {
        String className = childSpec.query().getModelClass().getName();
        FactoryBuild.createList(childSpec, frame.nested(parent, className), childSpec.effectiveCount());
    }

    private static void attach(
            Model saved, Relation<?, ?> relation, HasLink link, FactorySpec<?> spec, FactoryRun frame) {
        requireAttachKind(relation, link.relation(), saved);
        BelongsToMany<?> many = (BelongsToMany<?>) relation;
        if (link.existing() != null) {
            many.attach(link.existing(), link.pivotMap());
            return;
        }
        attachCreated(link.factory().bound().withConnectionIfAbsent(spec.connection()), many, link, saved, frame);
    }

    private static <R extends Model> void attachCreated(
            FactorySpec<R> relatedSpec, BelongsToMany<?> many, HasLink link, Model saved, FactoryRun frame) {
        String className = relatedSpec.query().getModelClass().getName();
        List<R> models = FactoryBuild.createList(relatedSpec, frame.nested(saved, className),
                relatedSpec.effectiveCount());
        for (int i = 0; i < models.size(); i++) {
            many.attach(models.get(i), pivotFor(link, saved, i));
        }
    }

    private static Map<String, Object> pivotFor(HasLink link, Model saved, int index) {
        if (link.pivotMap() != null) {
            return new LinkedHashMap<>(link.pivotMap());
        }
        if (link.pivotFunction() != null) {
            Map<String, Object> computed = link.pivotFunction().apply(saved);
            return computed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(computed);
        }
        if (link.pivotList() != null) {
            return new LinkedHashMap<>(link.pivotList().get(index % link.pivotList().size()));
        }
        return new LinkedHashMap<>();
    }
}

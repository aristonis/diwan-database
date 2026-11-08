package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;

/**
 * The {@code whereBelongsTo} condition: the builder's foreign key in the owners' keys.
 */
final class WhereBelongsTo {

    private WhereBelongsTo() {
    }

    /**
     * Adds the condition to the builder.
     *
     * @param builder the builder, never {@code null}
     * @param related the owners, never {@code null}
     * @param relation the belongs-to relation name, or {@code null} to derive it from the first owner
     * @param or {@code true} to join with {@code or}
     * @throws IllegalArgumentException if the list is empty, an owner is of another class or has no key
     * @throws RelationNotFoundException if the name is unknown or not a belongs-to relation
     */
    static void add(EloquentBuilder<?> builder, List<? extends Model> related, String relation, boolean or) {
        if (related.isEmpty()) {
            throw new IllegalArgumentException("Collection given to whereBelongsTo may not be empty");
        }
        String name = relation != null
                ? relation
                : InverseRelations.camel(Objects.requireNonNull(related.get(0), "related").getClass().getSimpleName());
        Model parent = builder.getModel();
        Relation<?, ?> resolved = parent.relation(name);
        if (!(resolved instanceof BelongsTo)) {
            throw new RelationNotFoundException(parent.getClass(), name + " (a BelongsTo relation is required)",
                    RelationRegistry.of(parent).names());
        }
        BelongsTo<?> belongsTo = (BelongsTo<?>) resolved;
        List<Object> keys = keysOf(belongsTo, related, name);
        String column = belongsTo.getQualifiedForeignKeyName();
        builder.query(base -> {
            if (or) {
                base.orWhereIn(column, keys);
            } else {
                base.whereIn(column, keys);
            }
        });
    }

    private static List<Object> keysOf(BelongsTo<?> relation, List<? extends Model> related, String name) {
        Class<?> expected = relation.getRelated().getClass();
        String ownerKey = relation.getOwnerKeyName();
        List<Object> keys = new ArrayList<>(related.size());
        for (Model owner : related) {
            Objects.requireNonNull(owner, "related");
            if (!expected.isInstance(owner)) {
                throw new IllegalArgumentException("Relation [" + name + "] points at [" + expected.getName()
                        + "], not [" + owner.getClass().getName() + "]");
            }
            Object key = owner.getAttribute(ownerKey);
            if (key == null) {
                throw new IllegalArgumentException("A [" + owner.getClass().getName() + "] given to whereBelongsTo has"
                        + " no value for its owner key [" + ownerKey + "]");
            }
            keys.add(key);
        }
        return keys;
    }
}

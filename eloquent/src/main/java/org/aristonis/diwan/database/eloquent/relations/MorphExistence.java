package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.query.Builder;

/**
 * Adds the morph-to conditions of Laravel's {@code QueriesRelationships} to a query:
 * {@code hasMorph} (and its {@code whereHasMorph} forms) and {@code whereMorphedTo}.
 *
 * <p>{@code hasMorph} builds one group, an {@code or} of {@code (type = ? and exists(...))}, one
 * branch per owner class; the existence part is {@link RelationExistence#addResolved} on a
 * {@code BelongsTo} of that class. The wildcard {@code "*"} reads the distinct non-empty types of
 * the table first, on a query without global scopes. A form that also matches a parent with no
 * related row (such as {@code doesntHave}) matches a null type too, for every type list.
 * {@code whereMorphedTo} compares the type and key columns with bound values.
 */
final class MorphExistence {

    private static final String WILDCARD = "*";
    private static final String NEVER = "0 = 1";

    private MorphExistence() {
    }

    /**
     * Adds a morph existence group to a query.
     *
     * @param outer the query the group is added to, never {@code null}
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or the single element {@code "*"}, never
     *     {@code null}
     * @param operator the count comparison, one of {@code = < > <= >= <> !=}, never {@code null}
     * @param count the count compared with
     * @param or {@code true} to join the group with {@code or}
     * @param constraint the constraint on each owner query, given the owner class, or {@code null}
     * @throws NullPointerException if an argument other than {@code constraint} is {@code null}
     * @throws IllegalArgumentException if the operator is unknown, the name is a dot path or not a
     *     morph-to relation, or a type is neither a model class nor a string
     * @throws org.aristonis.diwan.database.eloquent.ClassMorphViolationException if a type cannot
     *     be resolved; nothing has been added
     */
    static void hasMorph(EloquentBuilder<?> outer, String relation, List<Object> types, String operator,
            long count, boolean or, BiConsumer<EloquentBuilder<?>, Class<? extends Model>> constraint) {
        Objects.requireNonNull(outer, "outer");
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(operator, "operator");
        RelationExistence.checkOperator(relation, operator);
        MorphTo<?> morphTo = morphToOf(outer, relation);
        boolean isNegative = isZeroMatch(operator, count);
        List<Class<? extends Model>> classes = resolveTypes(outer, morphTo, types);
        if (classes.isEmpty()) {
            if (!isNegative) {
                addRaw(outer, or);
            }
            return;
        }
        String typeColumn = outer.getModel().qualifyColumn(morphTo.getMorphType());
        Consumer<EloquentBuilder<?>> body = group -> {
            for (Class<? extends Model> type : classes) {
                group.orWhereGroup(branch -> addBranch(branch, morphTo, relation, type, operator, count, constraint));
            }
            if (isNegative) {
                group.orWhereNull(typeColumn);
            }
        };
        if (or) {
            outer.orWhereGroup(body);
        } else {
            outer.whereGroup(body);
        }
    }

    /**
     * Adds a condition that a morph-to relation points at a model, a class, a type or a set of
     * models.
     *
     * @param outer the query the condition is added to, never {@code null}
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param target a {@link Model}, a model {@link Class}, a morph alias or class name string, a
     *     non-empty {@link Collection} of models, or {@code null} for no type
     * @param not {@code true} to negate; a null type or key then counts as not pointing at the target
     * @param or {@code true} to join the condition with {@code or}
     * @throws NullPointerException if {@code outer} or {@code relation} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, the
     *     collection is empty or holds a non-model, a model has no key, or the target is of another
     *     kind
     * @throws org.aristonis.diwan.database.eloquent.ClassMorphViolationException if a class or
     *     alias cannot be resolved
     */
    static void whereMorphedTo(EloquentBuilder<?> outer, String relation, Object target, boolean not, boolean or) {
        Objects.requireNonNull(outer, "outer");
        MorphTo<?> morphTo = morphToOf(outer, relation);
        String typeColumn = outer.getModel().qualifyColumn(morphTo.getMorphType());
        String keyColumn = outer.getModel().qualifyColumn(morphTo.getForeignKeyName());
        if (target == null) {
            outer.query(base -> addNull(base, typeColumn, not, or));
            return;
        }
        if (!not && (target instanceof Class || target instanceof String)) {
            String alias = aliasOfType(outer, target);
            outer.query(base -> {
                if (or) {
                    base.orWhere(typeColumn, alias);
                } else {
                    base.where(typeColumn, alias);
                }
            });
            return;
        }
        Consumer<Builder> filler = fillerFor(target, typeColumn, keyColumn, outer, morphTo);
        Consumer<Builder> grouped = not
                ? nested -> {
                    nested.whereNot(filler);
                    nested.orWhereNull(typeColumn);
                    nested.orWhereNull(keyColumn);
                }
                : filler;
        outer.query(base -> {
            if (or) {
                base.orWhere(grouped);
            } else {
                base.where(grouped);
            }
        });
    }

    private static String aliasOfType(EloquentBuilder<?> outer, Object target) {
        return MorphMap.aliasOf(classOf(target, outer.getModel().getClass().getClassLoader()));
    }

    // Fills the group a model, a type or a collection target compares with.
    private static Consumer<Builder> fillerFor(Object target, String typeColumn, String keyColumn,
            EloquentBuilder<?> outer, MorphTo<?> morphTo) {
        if (target instanceof Model) {
            Model model = (Model) target;
            String alias = MorphMap.aliasOf(model.getClass());
            Object key = keyOf(model, morphTo);
            return group -> {
                group.where(typeColumn, alias);
                group.where(keyColumn, key);
            };
        }
        if (target instanceof Class || target instanceof String) {
            String alias = aliasOfType(outer, target);
            return group -> group.where(typeColumn, alias);
        }
        if (target instanceof Collection) {
            Map<String, List<Object>> keysByType = keysByType((Collection<?>) target, morphTo);
            return group -> {
                for (Map.Entry<String, List<Object>> entry : keysByType.entrySet()) {
                    group.orWhere(nested -> {
                        nested.where(typeColumn, entry.getKey());
                        nested.whereIn(keyColumn, entry.getValue());
                    });
                }
            };
        }
        throw new IllegalArgumentException("whereMorphedTo takes a model, a model class, a morph alias or a"
                + " collection of models, not " + target.getClass().getName());
    }

    private static Map<String, List<Object>> keysByType(Collection<?> models, MorphTo<?> morphTo) {
        if (models.isEmpty()) {
            throw new IllegalArgumentException("The collection given to whereMorphedTo may not be empty");
        }
        Map<String, List<Object>> keysByType = new LinkedHashMap<>();
        for (Object element : models) {
            if (!(element instanceof Model)) {
                throw new IllegalArgumentException("whereMorphedTo collections hold models, not "
                        + (element == null ? "null" : element.getClass().getName()));
            }
            Model model = (Model) element;
            String alias = MorphMap.aliasOf(model.getClass());
            List<Object> keys = keysByType.get(alias);
            if (keys == null) {
                keys = new ArrayList<>();
                keysByType.put(alias, keys);
            }
            keys.add(keyOf(model, morphTo));
        }
        return keysByType;
    }

    // The relation's owner key, else the model's own key name, as Laravel's whereMorphedTo reads it.
    private static Object keyOf(Model model, MorphTo<?> morphTo) {
        String keyName = morphTo.getOwnerKeyName().orElse(model.getKeyName());
        Object key = keyName == null ? null : model.getAttribute(keyName);
        if (key == null) {
            throw new IllegalArgumentException("Model [" + model.getClass().getName()
                    + "] has no key to compare with");
        }
        return key;
    }

    private static void addBranch(EloquentBuilder<?> branch, MorphTo<?> morphTo, String relation,
            Class<? extends Model> type, String operator, long count,
            BiConsumer<EloquentBuilder<?>, Class<? extends Model>> constraint) {
        String typeColumn = branch.getModel().qualifyColumn(morphTo.getMorphType());
        branch.where(typeColumn, MorphMap.aliasOf(type));
        Consumer<EloquentBuilder<?>> typed = constraint == null ? null : query -> constraint.accept(query, type);
        RelationExistence.addResolved(branch, belongsToOn(type, morphTo, branch.getModel()), relation, operator,
                count, false, typed);
    }

    // The same construction MorphTo uses for its per-class eager helper.
    private static <M extends Model> BelongsTo<M> belongsToOn(Class<M> type, MorphTo<?> morphTo, Model child) {
        M instance = Access.eloquent().newRegistered(type, child);
        EloquentBuilder<M> query = Access.eloquent().newQueryOn(instance);
        String ownerKey = morphTo.getOwnerKeyName().orElse(query.getModel().getKeyName());
        return new BelongsTo<>(query, child, morphTo.getForeignKeyName(), ownerKey, null);
    }

    private static MorphTo<?> morphToOf(EloquentBuilder<?> outer, String relation) {
        Objects.requireNonNull(relation, "relation");
        if (relation.indexOf('.') >= 0) {
            throw new IllegalArgumentException("A morph query takes one relation name, not the path [" + relation
                    + "]");
        }
        Relation<?, ?> resolved = outer.getModel().relation(relation);
        if (!(resolved instanceof MorphTo)) {
            throw new IllegalArgumentException("Relation [" + relation + "] is a "
                    + resolved.getClass().getSimpleName() + ", not a morphTo relation");
        }
        return (MorphTo<?>) resolved;
    }

    // Every type is resolved, and so checked against the morph map, before the caller adds anything.
    private static List<Class<? extends Model>> resolveTypes(EloquentBuilder<?> outer, MorphTo<?> morphTo,
            List<Object> types) {
        ClassLoader loader = outer.getModel().getClass().getClassLoader();
        boolean isWildcard = types.size() == 1 && WILDCARD.equals(types.get(0));
        List<Object> given = isWildcard ? storedTypes(outer, morphTo) : types;
        Set<Class<? extends Model>> resolved = new LinkedHashSet<>();
        for (Object type : given) {
            resolved.add(classOf(type, loader));
        }
        for (Class<? extends Model> type : resolved) {
            MorphMap.aliasOf(type);
        }
        return new ArrayList<>(resolved);
    }

    // newModelQuery in Laravel: a plain query on a fresh, unaliased model, so no global scope applies.
    private static List<Object> storedTypes(EloquentBuilder<?> outer, MorphTo<?> morphTo) {
        Model fresh = Access.eloquent().newRegistered(outer.getModel().getClass(), outer.getModel());
        String column = fresh.qualifyColumn(morphTo.getMorphType());
        List<Object> stored = new ArrayList<>();
        for (Object value : fresh.newBaseQuery().distinct().pluck(column)) {
            if (value != null && !value.toString().isEmpty()) {
                stored.add(value.toString());
            }
        }
        return stored;
    }

    private static Class<? extends Model> classOf(Object type, ClassLoader loader) {
        Objects.requireNonNull(type, "type");
        if (type instanceof String) {
            return MorphMap.resolve((String) type, loader);
        }
        if (type instanceof Class && Model.class.isAssignableFrom((Class<?>) type)) {
            return ((Class<?>) type).asSubclass(Model.class);
        }
        throw new IllegalArgumentException("A morph type is a model class or an alias, not " + type);
    }

    // Whether a parent with no related row passes the comparison "0 operator count".
    private static boolean isZeroMatch(String operator, long count) {
        switch (operator) {
            case "=":
                return count == 0;
            case "<":
                return count > 0;
            case ">":
                return count < 0;
            case "<=":
                return count >= 0;
            case ">=":
                return count <= 0;
            default:
                return count != 0;
        }
    }

    private static void addRaw(EloquentBuilder<?> outer, boolean or) {
        if (or) {
            outer.orWhereRaw(NEVER);
        } else {
            outer.whereRaw(NEVER);
        }
    }

    private static void addNull(Builder base, String column, boolean isNotNull, boolean or) {
        if (isNotNull) {
            if (or) {
                base.orWhereNotNull(column);
            } else {
                base.whereNotNull(column);
            }
        } else if (or) {
            base.orWhereNull(column);
        } else {
            base.whereNull(column);
        }
    }
}

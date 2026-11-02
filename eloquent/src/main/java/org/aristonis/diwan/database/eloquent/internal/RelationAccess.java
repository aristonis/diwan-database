package org.aristonis.diwan.database.eloquent.internal;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.Relation;

/**
 * What the model package needs from the relation package's package-private members.
 *
 * <p>Implemented once, in the relation package, and registered by {@code Relation}'s static
 * initialiser through {@link Access#setRelations(RelationAccess)}. Callers in the model package
 * call {@code Relation.ensureInitialised()} first.
 *
 * <p>Internal: not API.
 *
 * @implSpec An implementation keeps no state of its own and is safe to call from any thread.
 */
public interface RelationAccess {

    /**
     * Eager loads relations onto models of one class.
     *
     * @param models the parent models, never {@code null}, all of one class; empty does nothing
     * @param relations the constraints by dot path, as parsed by
     *     {@link EloquentAccess#parseWith(String...)}, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a path
     *     names an unknown relation; no query has run
     * @throws IllegalStateException if a constraint sets a limit or an offset
     */
    void load(List<? extends Model> models, Map<String, Consumer<EloquentBuilder<?>>> relations);

    /**
     * Eager loads the relations that are not loaded yet on models of one class, level by level.
     *
     * @param models the parent models, never {@code null}, all of one class; empty does nothing
     * @param relations the constraints by dot path, as for {@link #load(List, Map)}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a path
     *     names an unknown relation
     * @throws IllegalStateException if a constraint sets a limit or an offset
     */
    void loadMissing(List<? extends Model> models, Map<String, Consumer<EloquentBuilder<?>>> relations);

    /**
     * Adds a relation existence condition ({@code has}, {@code whereHas} and their forms) to a
     * builder.
     *
     * @param outer the builder the condition is added to, never {@code null}
     * @param relation the relation name or dot path, never {@code null}
     * @param operator the count comparison: {@code =}, {@code <}, {@code >}, {@code <=},
     *     {@code >=}, {@code <>} or {@code !=}, never {@code null}
     * @param count the count compared with
     * @param or {@code true} to join the condition with {@code or}
     * @param constraint the constraint on the related query, or {@code null} for none
     * @throws NullPointerException if {@code outer}, {@code relation} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the operator is not one of the seven
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the path
     *     names an unknown relation
     */
    void addExistence(EloquentBuilder<?> outer, String relation, String operator, long count, boolean or,
            Consumer<EloquentBuilder<?>> constraint);

    /**
     * Resolves a relation by name through the model class's checked relation map.
     *
     * @param model the parent model, never {@code null}
     * @param name the relation name, never {@code null}
     * @return the unconstrained relation, its name set, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     model class has no relation of that name
     * @throws org.aristonis.diwan.database.eloquent.relations.InvalidRelationException if the
     *     model class's relation map fails its check
     */
    Relation<?, ?> resolve(Model model, String name);
}

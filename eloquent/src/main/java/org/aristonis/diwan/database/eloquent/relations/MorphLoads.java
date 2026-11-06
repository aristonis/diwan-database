package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;

/**
 * Laravel's {@code loadMorph} and {@code loadMorphCount} on models already in memory: the
 * owners a morph-to relation has loaded are grouped by class, and each class gets its own list of
 * relations to load or count.
 *
 * <p>A morph-to relation that is not loaded is loaded first, for all the models in one query per
 * owner class, as Laravel's attribute read lazy loads it. A model whose owner is {@code null} is
 * skipped, and the models themselves are not read again. A class with no entry
 * in the map, or an empty list, loads nothing. Stateless; the models are not safe to share across
 * threads.
 */
final class MorphLoads {

    private static final String ANY_COLUMN = "*";

    private static final String COUNT = "count";

    private MorphLoads() {
    }

    /**
     * Loads, or counts, relations on the owners a morph-to relation has loaded, per owner class.
     *
     * @param models the models holding the morph-to relation, never {@code null}
     * @param relation the morph-to relation's name, never {@code null}
     * @param relations the relation paths by owner class, never {@code null}; with
     *     {@code isCount} they are relation names, each optionally {@code "name as alias"}
     * @param isCount {@code true} to load {@code count(*)} per relation, {@code false} to eager
     *     load the relations
     * @throws NullPointerException if an argument, a class key or a list entry is {@code null}
     * @throws RelationNotFoundException if a path names no relation of its owner class; checked
     *     before the class's first query
     * @throws IllegalArgumentException if the models are not all of one class
     * @throws IllegalStateException if the bridge is not initialised
     */
    static void loadMorph(Collection<? extends Model> models, String relation,
            Map<Class<? extends Model>, List<String>> relations, boolean isCount) {
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(relations, "relations");
        Relation.ensureInitialised();
        loadOwners(models, relation);
        for (Map.Entry<Class<? extends Model>, List<Model>> group : ownersByClass(models, relation).entrySet()) {
            List<String> names = relations.get(group.getKey());
            if (names == null || names.isEmpty()) {
                continue;
            }
            if (isCount) {
                countOn(group.getValue(), names);
            } else {
                EagerLoader.load(group.getValue(), Access.eloquent().parseWith(namesOf(names)));
            }
        }
    }

    private static void loadOwners(Collection<? extends Model> models, String relation) {
        List<Model> all = new ArrayList<>(models.size());
        for (Model model : models) {
            all.add(Objects.requireNonNull(model, "model"));
        }
        EagerLoader.loadMissing(all, Collections.singletonMap(relation, EagerLoader.NO_OP));
    }

    private static Map<Class<? extends Model>, List<Model>> ownersByClass(Collection<? extends Model> models,
            String relation) {
        Map<Class<? extends Model>, List<Model>> groups = new LinkedHashMap<>();
        // One owner instance may be shared by several models; it joins its class group once.
        Set<Model> seen = Collections.newSetFromMap(new IdentityHashMap<Model, Boolean>());
        for (Model model : models) {
            Objects.requireNonNull(model, "model");
            Object owner = model.getRelation(relation);
            if (!(owner instanceof Model)) {
                continue;
            }
            if (!seen.add((Model) owner)) {
                continue;
            }
            Class<? extends Model> type = ((Model) owner).getClass();
            List<Model> group = groups.get(type);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(type, group);
            }
            group.add((Model) owner);
        }
        return groups;
    }

    private static void countOn(List<Model> owners, List<String> names) {
        Map<String, Consumer<EloquentBuilder<?>>> counts = new LinkedHashMap<>();
        for (String name : names) {
            counts.put(Objects.requireNonNull(name, "relation"), EagerLoader.NO_OP);
        }
        Access.relations().loadAggregate(owners, counts, ANY_COLUMN, COUNT);
    }

    private static String[] namesOf(List<String> names) {
        for (String name : names) {
            Objects.requireNonNull(name, "relation");
        }
        return names.toArray(new String[0]);
    }
}

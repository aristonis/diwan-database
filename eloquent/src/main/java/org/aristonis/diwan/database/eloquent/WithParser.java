package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.Relation;

/**
 * Turns eager load paths into constraints by path, as Laravel's {@code parseWithRelations},
 * {@code addNestedWiths} and {@code createSelectWithConstraint} do.
 *
 * <ul>
 *   <li>{@code "a.b.c"} registers {@code a} and {@code a.b} with {@link #NO_OP} unless they are
 *       already present, then {@code a.b.c}.
 *   <li>{@code "name:c1,c2"} registers {@code name} with a constraint that selects the trimmed
 *       columns; the colon belongs to the last segment, so {@code "a.b:id"} constrains {@code a.b}.
 *   <li>The same path twice runs both constraints, first added first ({@link Consumer#andThen});
 *       Laravel keeps only the last one; running both loses no constraint. A {@code withMorph}
 *       constraint keeps its per-class options through the merge.
 * </ul>
 *
 * <p>{@link #parseAggregates(String...)} keys by the raw name, {@code as} alias included, and
 * does not expand dot paths. Names are not checked here: a path that names no relation fails
 * when it is loaded, as in Laravel. Stateless and safe to call from any thread.
 */
final class WithParser {

    /** The constraint of a path given without one: it changes nothing. */
    static final Consumer<EloquentBuilder<?>> NO_OP = query -> {
    };

    private WithParser() {
    }

    /**
     * Parses paths given as text, such as {@code "comments.author"} and {@code "author:id,name"}.
     *
     * @param relations the paths, none {@code null}
     * @return the constraints by path, parents before children, in call order, modifiable
     * @throws NullPointerException if {@code relations} or a path is {@code null}
     * @throws IllegalArgumentException if a {@code name:} path lists no column or an empty one
     */
    static Map<String, Consumer<EloquentBuilder<?>>> parse(String... relations) {
        Objects.requireNonNull(relations, "relations");
        Map<String, Consumer<EloquentBuilder<?>>> results = new LinkedHashMap<>();
        for (String relation : relations) {
            String path = Objects.requireNonNull(relation, "relation");
            int colon = path.indexOf(':');
            if (colon < 0) {
                put(results, path, NO_OP);
            } else {
                put(results, path.substring(0, colon), selectConstraint(path, path.substring(colon + 1)));
            }
        }
        return results;
    }

    /**
     * Parses paths given with their constraints; a colon in a path is part of its name, as in
     * Laravel's array form.
     *
     * @param relations the constraints by path, no key or value {@code null}
     * @return the constraints by path, parents before children, in the map's order, modifiable
     * @throws NullPointerException if {@code relations}, a path or a constraint is {@code null}
     */
    static Map<String, Consumer<EloquentBuilder<?>>> parse(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        Objects.requireNonNull(relations, "relations");
        Map<String, Consumer<EloquentBuilder<?>>> results = new LinkedHashMap<>();
        for (Map.Entry<String, Consumer<EloquentBuilder<?>>> entry : relations.entrySet()) {
            put(results, Objects.requireNonNull(entry.getKey(), "relation"),
                    Objects.requireNonNull(entry.getValue(), "constraint"));
        }
        return results;
    }

    /**
     * Parses aggregate relation names given as text, such as {@code "posts"},
     * {@code "posts as total"} and {@code "posts:id"}, without expanding dot paths.
     *
     * <p>The key is the raw name text before alias splitting, so {@code "posts"} and
     * {@code "posts as p2"} give two entries. A dotted name stays one key and is refused later,
     * where the model is known. The select constraint of a {@code name:} form is kept but has no
     * effect inside an aggregate subselect.
     *
     * @param relations the names, none {@code null}; empty gives an empty map
     * @return the constraints by raw name, in call order, modifiable
     * @throws NullPointerException if {@code relations} or a name is {@code null}
     * @throws IllegalArgumentException if a {@code name:} form lists no column or an empty one
     */
    static Map<String, Consumer<EloquentBuilder<?>>> parseAggregates(String... relations) {
        Objects.requireNonNull(relations, "relations");
        Map<String, Consumer<EloquentBuilder<?>>> results = new LinkedHashMap<>();
        for (String relation : relations) {
            String name = Objects.requireNonNull(relation, "relation");
            int colon = name.indexOf(':');
            if (colon < 0) {
                combine(results, name, NO_OP);
            } else {
                combine(results, name.substring(0, colon), selectConstraint(name, name.substring(colon + 1)));
            }
        }
        return results;
    }

    /**
     * Parses aggregate relation names given with their constraints, without expanding dot paths;
     * a colon in a name is part of it, as in Laravel's array form.
     *
     * @param relations the constraints by raw name, no key or value {@code null}; empty gives an
     *     empty map
     * @return the constraints by raw name, in the map's order, modifiable
     * @throws NullPointerException if {@code relations}, a name or a constraint is {@code null}
     */
    static Map<String, Consumer<EloquentBuilder<?>>> parseAggregates(
            Map<String, Consumer<EloquentBuilder<?>>> relations) {
        Objects.requireNonNull(relations, "relations");
        Map<String, Consumer<EloquentBuilder<?>>> results = new LinkedHashMap<>();
        for (Map.Entry<String, Consumer<EloquentBuilder<?>>> entry : relations.entrySet()) {
            combine(results, Objects.requireNonNull(entry.getKey(), "relation"),
                    Objects.requireNonNull(entry.getValue(), "constraint"));
        }
        return results;
    }

    /**
     * Adds parsed paths to a map of eager loads; a path already present runs its old constraint,
     * then the new one.
     *
     * @param target the eager loads to add to, never {@code null}, changed in place
     * @param added the parsed paths, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    static void merge(Map<String, Consumer<EloquentBuilder<?>>> target,
            Map<String, Consumer<EloquentBuilder<?>>> added) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(added, "added");
        for (Map.Entry<String, Consumer<EloquentBuilder<?>>> entry : added.entrySet()) {
            combine(target, entry.getKey(), entry.getValue());
        }
    }

    private static void put(Map<String, Consumer<EloquentBuilder<?>>> results, String path,
            Consumer<EloquentBuilder<?>> constraint) {
        int dot = path.indexOf('.');
        while (dot >= 0) {
            results.putIfAbsent(path.substring(0, dot), NO_OP);
            dot = path.indexOf('.', dot + 1);
        }
        combine(results, path, constraint);
    }

    private static void combine(Map<String, Consumer<EloquentBuilder<?>>> results, String path,
            Consumer<EloquentBuilder<?>> constraint) {
        Consumer<EloquentBuilder<?>> existing = results.get(path);
        if (existing == null || existing == NO_OP) {
            results.put(path, constraint);
        } else if (constraint != NO_OP) {
            Relation.ensureInitialised();
            results.put(path, Access.relations().combineEager(existing, constraint));
        }
    }

    private static Consumer<EloquentBuilder<?>> selectConstraint(String path, String columnList) {
        List<String> columns = new ArrayList<>();
        for (String column : columnList.split(",", -1)) {
            String trimmed = column.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Eager load path [" + path + "] lists an empty column");
            }
            columns.add(trimmed);
        }
        Object[] selected = columns.toArray();
        return query -> query.select(selected.clone());
    }
}

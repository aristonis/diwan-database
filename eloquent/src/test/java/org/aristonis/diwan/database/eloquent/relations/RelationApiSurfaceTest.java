package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The public read methods of each relation kind. The belongsToMany kind has the whole set of finders,
 * iterators and pages; the one-to-many, one-to-one and belongsTo kinds have none of them.
 */
class RelationApiSurfaceTest {

    private static final List<String> READ_METHODS = Arrays.asList(
            "find", "findMany", "findOrFail", "findOr", "first", "firstOrFail", "firstOr", "firstWhere", "sole",
            "chunk", "chunkById", "each", "lazy", "lazyById", "cursor",
            "paginate", "simplePaginate", "cursorPaginate");

    @Test
    void hasManyHasNoReadMethods() {
        assertNoneOf(HasMany.class, READ_METHODS);
    }

    @Test
    void hasOneHasNoNewReadMethods() {
        // HasOne's own first() predates the belongsToMany reads and is part of its existing API.
        assertNoneOf(HasOne.class, withoutFirst(READ_METHODS));
    }

    @Test
    void belongsToHasNoReadMethods() {
        assertNoneOf(BelongsTo.class, READ_METHODS);
    }

    @Test
    void belongsToManyHasEveryReadMethod() {
        Set<String> names = publicNames(BelongsToMany.class);

        for (String name : READ_METHODS) {
            assertTrue(names.contains(name), "BelongsToMany must have " + name);
        }
    }

    private static void assertNoneOf(Class<?> type, List<String> names) {
        Set<String> publicNames = publicNames(type);
        for (String name : names) {
            assertFalse(publicNames.contains(name), type.getSimpleName() + " must not have " + name);
        }
    }

    private static Set<String> publicNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        for (Method method : type.getMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    private static List<String> withoutFirst(List<String> names) {
        List<String> kept = new ArrayList<>();
        for (String name : names) {
            if (!"first".equals(name)) {
                kept.add(name);
            }
        }
        return kept;
    }
}

package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.aristonis.diwan.database.query.BindingType;
import org.junit.jupiter.api.Test;

/**
 * Guards the aggregate constraint carry-over against a clause kind added to the query
 * builder later. Every state accessor of the builder and every binding bucket must be
 * classified here as carried, ignored, refused or irrelevant, mirroring what
 * {@code RelationAggregates} does with it, so a new kind cannot be silently dropped from an
 * {@code withCount} constraint.
 */
class AggregateCarryOverGuardTest {

    /** Accessors whose state {@code RelationAggregates} copies onto the aggregate subquery. */
    private static final Set<String> CARRIED = Set.of("getWheres", "getLimit", "getOffset", "getGroups");

    /** Accessors whose state an aggregate constraint may set and is deliberately dropped. */
    private static final Set<String> IGNORED = Set.of("getColumns", "getOrders");

    /** Accessors whose state makes {@code RelationAggregates} refuse the constraint. */
    private static final Set<String> REFUSED = Set.of("isDistinct", "getFrom", "getFromExpression", "hasJoins",
            "getJoins", "getHavings", "getUnions", "getUnionOrders", "getUnionLimit", "getUnionOffset",
            "getAggregate");

    /** Accessors that expose no clause: collaborators, rendering and the binding views. */
    private static final Set<String> IRRELEVANT = Set.of("getConnection", "getGrammar", "getProcessor", "toSql",
            "toRawSql", "getBindings", "getRawBindings");

    private static final Set<BindingType> CARRIED_BINDINGS = EnumSet.of(BindingType.WHERE, BindingType.GROUP_BY);

    private static final Set<BindingType> IGNORED_BINDINGS = EnumSet.of(BindingType.SELECT, BindingType.ORDER);

    private static final Set<BindingType> REFUSED_BINDINGS = EnumSet.of(BindingType.FROM, BindingType.JOIN,
            BindingType.HAVING, BindingType.UNION, BindingType.UNION_ORDER);

    @Test
    void everyBuilderStateAccessorIsClassified() {
        Set<String> known = new TreeSet<>();
        known.addAll(CARRIED);
        known.addAll(IGNORED);
        known.addAll(REFUSED);
        known.addAll(IRRELEVANT);
        List<String> unclassified = new ArrayList<>();
        for (String name : stateAccessors()) {
            if (!known.contains(name)) {
                unclassified.add(name);
            }
        }
        assertTrue(unclassified.isEmpty(), "Builder state accessor(s) " + unclassified
                + " are not classified: decide in RelationAggregates (refusedKind/carryOver) whether an aggregate"
                + " constraint carries, ignores or refuses it, then add the name to this test");
    }

    @Test
    void classificationNamesNoAccessorThatNoLongerExists() {
        Set<String> actual = new TreeSet<>(stateAccessors());
        Set<String> known = new TreeSet<>();
        known.addAll(CARRIED);
        known.addAll(IGNORED);
        known.addAll(REFUSED);
        known.addAll(IRRELEVANT);
        known.removeAll(actual);
        assertTrue(known.isEmpty(), "Classified accessor(s) " + known + " no longer exist on the builder");
    }

    @Test
    void classificationSetsDoNotOverlap() {
        int total = CARRIED.size() + IGNORED.size() + REFUSED.size() + IRRELEVANT.size();
        Set<String> union = new TreeSet<>();
        union.addAll(CARRIED);
        union.addAll(IGNORED);
        union.addAll(REFUSED);
        union.addAll(IRRELEVANT);
        assertEquals(total, union.size());
    }

    @Test
    void everyBindingTypeIsClassified() {
        Set<BindingType> known = EnumSet.noneOf(BindingType.class);
        known.addAll(CARRIED_BINDINGS);
        known.addAll(IGNORED_BINDINGS);
        known.addAll(REFUSED_BINDINGS);
        Set<BindingType> unclassified = EnumSet.allOf(BindingType.class);
        unclassified.removeAll(known);
        assertTrue(unclassified.isEmpty(), "BindingType constant(s) " + unclassified
                + " are not classified: decide in RelationAggregates (refusedKind/carryOver) whether an aggregate"
                + " constraint carries, ignores or refuses them, then add them to this test");
    }

    @Test
    void bindingClassificationsDoNotOverlap() {
        int total = CARRIED_BINDINGS.size() + IGNORED_BINDINGS.size() + REFUSED_BINDINGS.size();
        Set<BindingType> union = EnumSet.noneOf(BindingType.class);
        union.addAll(CARRIED_BINDINGS);
        union.addAll(IGNORED_BINDINGS);
        union.addAll(REFUSED_BINDINGS);
        assertEquals(total, union.size());
    }

    @Test
    void refusedBindingBucketsMatchTheOnesRelationAggregatesChecks() throws ReflectiveOperationException {
        Field field = RelationAggregates.class.getDeclaredField("REFUSED_BINDINGS");
        field.setAccessible(true);
        Map<?, ?> actual = (Map<?, ?>) field.get(null);
        assertEquals(REFUSED_BINDINGS, EnumSet.copyOf(castKeys(actual)));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Collection<BindingType> castKeys(Map<?, ?> map) {
        return (java.util.Collection<BindingType>) (java.util.Collection<?>) map.keySet();
    }

    /** The public, non-static, no-argument, non-void methods of the builder's state root class in core. */
    private static List<String> stateAccessors() {
        Class<?> root;
        try {
            root = Class.forName("org.aristonis.diwan.database.query.BuilderState");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("The builder state root class moved: update this guard", e);
        }
        List<String> names = new ArrayList<>();
        for (Method m : root.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 0 && m.getReturnType() != void.class
                    && !names.contains(m.getName())) {
                names.add(m.getName());
            }
        }
        return names;
    }
}

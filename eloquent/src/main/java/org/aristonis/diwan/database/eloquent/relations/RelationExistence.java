package org.aristonis.diwan.database.eloquent.relations;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;
import org.aristonis.diwan.database.query.Builder;

/**
 * Adds relation existence conditions ({@code has}, {@code whereHas} and their forms) to a
 * query, as Laravel's {@code QueriesRelationships::has}.
 *
 * <p>{@code >= 1} and {@code < 1} compile to {@code exists} and {@code not exists}; any other
 * operator and count compile to {@code (select count(*) ...) op ?}. A constraint always runs
 * inside a where group, which is left out when the constraint adds no condition. A dot
 * path nests one existence query per segment; the operator and count apply to the last one.
 * A self relation numbers its table alias from the outermost query's {@link AliasCounter}.
 */
final class RelationExistence {

    /** The operators a count may be compared with. */
    private static final Set<String> OPERATORS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("=", "<", ">", "<=", ">=", "<>", "!=")));

    private static final String AT_LEAST = ">=";
    private static final String LESS_THAN = "<";
    private static final String TABLE_ALIAS = " as ";

    private RelationExistence() {
    }

    /**
     * Adds an existence condition on a relation, or a dot path of relations, to a query.
     *
     * @param outer the query the condition is added to, never {@code null}
     * @param relation the relation name or dot path, never {@code null}
     * @param operator the count comparison, one of {@code = < > <= >= <> !=}, never {@code null}
     * @param count the count compared with
     * @param or {@code true} to join the condition with {@code or}
     * @param constraint the constraint on the related query, or {@code null} for none
     * @throws IllegalArgumentException if the operator is not one of the seven
     * @throws RelationNotFoundException if the path names an unknown relation
     */
    static void add(EloquentBuilder<?> outer, String relation, String operator, long count, boolean or,
            Consumer<EloquentBuilder<?>> constraint) {
        if (!OPERATORS.contains(operator)) {
            throw new IllegalArgumentException("Unknown operator [" + operator + "] for relation [" + relation
                    + "]; use one of " + new TreeSet<>(OPERATORS));
        }
        int dot = relation.indexOf('.');
        if (dot >= 0) {
            addNested(outer, relation.substring(0, dot), relation.substring(dot + 1), operator, count, or, constraint);
            return;
        }
        Relation<?, ?> resolved = outer.getModel().relation(relation);
        AliasCounter aliases = Access.eloquent().aliases(outer);
        EloquentBuilder<?> sub = resolved.existenceQuery(outer, aliases);
        Access.eloquent().shareAliases(outer, sub);
        if (constraint != null) {
            constrain(sub, relation, constraint);
        }
        if (isExistsCheck(operator, count)) {
            Builder exists = sub.toBase();
            boolean not = LESS_THAN.equals(operator);
            outer.query(base -> addExists(base, exists, or, not));
            return;
        }
        sub.query(base -> base.selectRaw("count(*)"));
        Builder counted = sub.toBase();
        Long value = count;
        outer.query(base -> {
            if (or) {
                base.orWhere(counted, operator, value);
            } else {
                base.where(counted, operator, value);
            }
        });
    }

    /**
     * Reports whether a related query and a parent query read the same table, ignoring any
     * alias, so a self relation is aliased at every nesting level.
     *
     * @param related the related query, never {@code null}
     * @param parent the parent query, never {@code null}
     * @return whether both read the same table
     */
    static boolean isSelfRelation(EloquentBuilder<?> related, EloquentBuilder<?> parent) {
        String relatedFrom = related.toBase().getFrom();
        String parentFrom = parent.toBase().getFrom();
        return relatedFrom != null && parentFrom != null && table(relatedFrom).equals(table(parentFrom));
    }

    // The group only merges wheres and scope removals back into the subquery, so a join added
    // inside it would be dropped without a word; it is refused instead.
    private static void constrain(EloquentBuilder<?> sub, String relation, Consumer<EloquentBuilder<?>> constraint) {
        Builder[] group = new Builder[1];
        Access.eloquent().constraintGroup(sub, q -> {
            constraint.accept(q);
            group[0] = q.toBase();
        });
        if (group[0] != null && !group[0].getJoins().isEmpty()) {
            throw new IllegalStateException("The constraint of relation [" + relation
                    + "] adds a join; an existence constraint supports only where clauses");
        }
    }

    /**
     * Returns the table name of a {@code from} clause, without any alias.
     *
     * @param from the from clause text, never {@code null}
     * @return the table name, never {@code null}
     */
    static String table(String from) {
        int alias = from.toLowerCase(Locale.ROOT).indexOf(TABLE_ALIAS);
        return alias < 0 ? from.trim() : from.substring(0, alias).trim();
    }

    // Laravel hasNested: a doesntHave path is "not exists (a ... exists (b))", so only the head
    // is negated; any other operator and count go to the last segment.
    private static void addNested(EloquentBuilder<?> outer, String head, String rest, String operator, long count,
            boolean or, Consumer<EloquentBuilder<?>> constraint) {
        boolean isDoesntHave = isExistsCheck(operator, count) && LESS_THAN.equals(operator);
        String innerOperator = isDoesntHave ? AT_LEAST : operator;
        add(outer, head, isDoesntHave ? LESS_THAN : AT_LEAST, 1, or,
                q -> add(q, rest, innerOperator, count, false, constraint));
    }

    private static boolean isExistsCheck(String operator, long count) {
        return count == 1 && (AT_LEAST.equals(operator) || LESS_THAN.equals(operator));
    }

    private static void addExists(Builder base, Builder exists, boolean or, boolean not) {
        if (or) {
            if (not) {
                base.orWhereNotExists(exists);
            } else {
                base.orWhereExists(exists);
            }
        } else if (not) {
            base.whereNotExists(exists);
        } else {
            base.whereExists(exists);
        }
    }
}

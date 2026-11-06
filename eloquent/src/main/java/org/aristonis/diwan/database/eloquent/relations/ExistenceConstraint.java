package org.aristonis.diwan.database.eloquent.relations;

import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.query.Builder;

/**
 * Runs a user constraint on a relation's existence query and prepares that query for a
 * single-value subselect, shared by the existence conditions ({@code has}, {@code whereHas}) and
 * the aggregates ({@code withCount}, {@code withSum} and the others).
 *
 * <p>The constraint runs on the subquery itself, so it may add joins, orders, a limit and groups,
 * and a scope removal it makes reaches the subquery. Only the wheres it added are wrapped in one
 * where group, so an {@code or} inside it cannot widen the relation key condition.
 */
final class ExistenceConstraint {

    private ExistenceConstraint() {
    }

    /**
     * Runs a constraint on an existence query and groups the wheres it added.
     *
     * @param sub the existence query, never {@code null}
     * @param constraint the user constraint, never {@code null}
     */
    static void run(EloquentBuilder<?> sub, Consumer<EloquentBuilder<?>> constraint) {
        // Laravel callScope: only the wheres the closure added are grouped.
        int[] start = new int[1];
        sub.query(base -> start[0] = base.getWheres().size());
        constraint.accept(sub);
        sub.query(base -> base.groupWheresAt(start[0], true));
    }

    /**
     * Prepares an existence query to return one value: refuses a shape that would return many rows
     * or count something else, then clears the columns, the orders, the limit and the offset.
     *
     * @param sub the existence query after the constraint ran, never {@code null}
     * @param relation the relation name, used in the message, never {@code null}
     * @param kind what the subquery is for, used in the message, such as {@code "count existence"}
     * @throws IllegalArgumentException if the constraint added a group by, a having, distinct or a union
     */
    static void prepareValue(EloquentBuilder<?> sub, String relation, String kind) {
        // An order, limit or offset means nothing in a one-row subquery and PostgreSQL rejects an
        // order by. Laravel also resets the columns and the select bindings, so a select("id") in
        // the constraint cannot end up beside count(*).
        sub.query(base -> {
            refuseMany(base, relation, kind);
            base.clearColumns().reorder().clearLimitAndOffset();
        });
    }

    private static void refuseMany(Builder base, String relation, String kind) {
        if (!base.getGroups().isEmpty() || !base.getHavings().isEmpty() || base.isDistinct()
                || !base.getUnions().isEmpty()) {
            throw new IllegalArgumentException("The constraint of relation [" + relation
                    + "]: groupBy/having/distinct/union in a " + kind + " constraint is not supported");
        }
    }
}

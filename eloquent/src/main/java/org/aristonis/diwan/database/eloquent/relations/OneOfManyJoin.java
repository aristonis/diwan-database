package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.JoinClause;
import org.aristonis.diwan.database.query.QueryGrammar;

/**
 * Builds the subquery chain of a one-of-many relation and joins it to the relation's query:
 * Laravel's {@code CanBeOneOfMany::ofMany} loop.
 *
 * <p>Each column gets one subquery that groups the related rows by the partition columns and
 * selects {@code max(column)} or {@code min(column)} as {@code column_aggregate}, plus a
 * {@code min} of every earlier column. A subquery joins the one before it on those earlier
 * aggregates, so the chain narrows to the rows that win on every column in turn. The last one is
 * inner-joined to the main query, which keeps the winning row of each partition.
 *
 * <p>Subqueries are fresh queries on the related class that keep its global scopes unless the
 * main query removed them, and every column name is a validated identifier quoted by the grammar.
 *
 * @param <R> the related model class
 */
final class OneOfManyJoin<R extends Model> {

    /** How the main query is being built. */
    enum Mode {
        /** A lazy read: the innermost subquery is limited to the parent's key. */
        LAZY,
        /** An eager chunk: the innermost subquery is limited to the chunk's keys. */
        EAGER,
        /** An existence query: correlated by the outer query, no subquery filter. */
        EXISTENCE
    }

    private static final String ALL = "*";
    private static final String AGGREGATE_SUFFIX = "_aggregate";

    private final EloquentBuilder<R> target;
    private final OneOfMany spec;
    private final OneOfManyTarget<R> relation;

    private OneOfManyJoin(EloquentBuilder<R> target, OneOfMany spec, OneOfManyTarget<R> relation) {
        this.target = target;
        this.spec = spec;
        this.relation = relation;
    }

    /**
     * Joins the one-of-many subquery chain to the target query and, outside an existence query,
     * makes sure the related columns are selected.
     *
     * @param target the main query, never {@code null}
     * @param spec the one-of-many description, never {@code null}
     * @param relation the relation the query belongs to, never {@code null}
     * @param mode how the query is built, never {@code null}
     * @param keys the eager chunk's keys, never {@code null}; empty outside eager loading
     * @param <R> the related model class
     * @throws NullPointerException if an argument is {@code null}
     */
    static <R extends Model> void apply(EloquentBuilder<R> target, OneOfMany spec, OneOfManyTarget<R> relation,
            Mode mode, List<Object> keys) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(keys, "keys");
        new OneOfManyJoin<>(target, spec, relation).run(mode, keys);
    }

    private void run(Mode mode, List<Object> keys) {
        int count = spec.columns().size();
        EloquentBuilder<R> previous = null;
        String alias = null;
        QueryGrammar grammar = null;
        for (int index = 0; index < count; index++) {
            EloquentBuilder<R> sub = freshSub();
            if (alias == null) {
                alias = relation.oneOfManyAlias(sub.getModel().getTable());
                grammar = sub.toBase().getGrammar();
            }
            relation.prepareOneOfManySubQuery(sub);
            selectAggregates(sub, grammar, index);
            partition(sub);
            if (previous != null) {
                joinPrevious(sub, previous, alias, chain(index - 1));
            } else {
                relation.filterOneOfManySubQuery(sub, mode, keys);
            }
            if (spec.constraint() != null) {
                spec.constraint().accept(sub);
            }
            previous = sub;
        }
        joinMain(previous, alias, chain(count - 1));
        if (mode != Mode.EXISTENCE) {
            selectRelatedColumns(target, relation.relatedQualifier(target), mode == Mode.EAGER);
        }
    }

    /** A fresh related query that keeps the target's global scopes minus the ones it removed. */
    private EloquentBuilder<R> freshSub() {
        EloquentBuilder<R> sub = Access.eloquent().newQueryOn(target.getModel());
        sub.withoutGlobalScopes(target.removedScopes());
        return sub;
    }

    /** The columns of step {@code index}: its own first, then each earlier one, newest first. */
    private List<String> chain(int index) {
        List<String> names = spec.columns();
        List<String> chain = new ArrayList<>(index + 1);
        for (int i = index; i >= 0; i--) {
            chain.add(names.get(i));
        }
        return chain;
    }

    private void selectAggregates(EloquentBuilder<R> sub, QueryGrammar grammar, int index) {
        List<String> chain = chain(index);
        for (int i = 0; i < chain.size(); i++) {
            String column = chain.get(i);
            String aggregate = i == 0 ? spec.aggregates().get(index) : OneOfMany.MIN;
            String sql = aggregate.toLowerCase(Locale.ROOT) + "(" + grammar.wrap(sub.getModel().qualifyColumn(column))
                    + ") as " + grammar.wrap(column + AGGREGATE_SUFFIX);
            sub.query(base -> base.selectRaw(sql));
        }
    }

    private void partition(EloquentBuilder<R> sub) {
        for (String column : relation.oneOfManyPartition(sub)) {
            sub.query(base -> base.addSelect(column).groupBy(column));
        }
    }

    private void joinPrevious(EloquentBuilder<R> sub, EloquentBuilder<R> previous, String alias,
            List<String> onColumns) {
        Builder previousBase = previous.toBase();
        String qualifier = sub.getModel().getTable();
        List<String> partition = relation.oneOfManyPartition(sub);
        sub.query(base -> base.joinSub(previousBase, alias,
                join -> addOn(join, alias, qualifier, onColumns, partition)));
    }

    private void joinMain(EloquentBuilder<R> last, String alias, List<String> onColumns) {
        Builder lastBase = last.toBase();
        String qualifier = relation.relatedQualifier(target);
        List<String> partition = relation.oneOfManyPartition(target);
        target.query(base -> base.joinSub(lastBase, alias,
                join -> addOn(join, alias, qualifier, onColumns, partition)));
    }

    private static void addOn(JoinClause join, String alias, String qualifier, List<String> columns,
            List<String> partition) {
        for (String column : columns) {
            join.on(alias + "." + column + AGGREGATE_SUFFIX, "=", qualifier + "." + column);
        }
        for (String column : partition) {
            join.on(alias + "." + bare(column), "=", column);
        }
    }

    /** Selects the related columns so the subquery's columns do not leak into the models. */
    private static void selectRelatedColumns(EloquentBuilder<?> main, String qualifier, boolean isEager) {
        List<Object> columns = main.toBase().getColumns();
        if (columns.isEmpty() || (columns.size() == 1 && ALL.equals(columns.get(0)))) {
            main.select(qualifier + ".*");
            return;
        }
        if (isEager) {
            qualifyBareColumns(main, columns, qualifier);
        }
    }

    private static void qualifyBareColumns(EloquentBuilder<?> main, List<Object> columns, String qualifier) {
        List<Object> qualified = new ArrayList<>(columns.size());
        boolean isChanged = false;
        for (Object column : columns) {
            Object next = column;
            if (column instanceof String && isBare((String) column)) {
                next = qualifier + "." + ((String) column).trim();
                isChanged = true;
            }
            qualified.add(next);
        }
        if (isChanged) {
            main.select(qualified.toArray());
        }
    }

    private static boolean isBare(String column) {
        String name = column.trim();
        if (name.isEmpty() || ALL.equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static String bare(String column) {
        return column.substring(column.lastIndexOf('.') + 1);
    }
}

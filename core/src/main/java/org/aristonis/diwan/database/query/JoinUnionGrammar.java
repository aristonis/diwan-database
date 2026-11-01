package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The join, union and insert-from-select layer of {@link QueryGrammar}, with the hooks a database
 * grammar overrides to update or delete with joins.
 *
 * <p>Package-private: its methods are documented as {@link QueryGrammar}'s own, and database
 * grammars override them there. The grammar is split into layers only to keep each file small.
 */
abstract class JoinUnionGrammar extends WhereGrammar {

    /** Creates the layer for a prefix already checked for {@code null}. */
    JoinUnionGrammar(String tablePrefix) {
        super(tablePrefix);
    }

    // ---- joins ----

    /**
     * Compiles the joins: {@code inner join "t" on ...}, each separated by a space. A join that
     * holds joins of its own compiles them inside parentheses with its table.
     *
     * @param query the query, never {@code null}
     * @param joins the joins, never {@code null}
     * @return the SQL, or an empty string when there are no joins
     * @throws UnsupportedOperationException if a condition is not supported
     */
    protected String compileJoins(Builder query, List<JoinClause> joins) {
        List<String> sql = new ArrayList<>(joins.size());
        for (JoinClause join : joins) {
            String table = wrapTable(join.getTable());
            String tableAndNested = join.joins.isEmpty() ? table : "(" + table + " " + compileJoins(query, join.joins) + ")";
            sql.add((join.getType() + " join " + tableAndNested + " " + compileWheres(join)).trim());
        }
        return String.join(" ", sql);
    }

    // ---- unions ----

    /**
     * Compiles one union part: {@code  union (select ...)}, with a leading space.
     *
     * @param union the union, never {@code null}
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support a clause
     */
    protected String compileUnion(Union union) {
        return (union.isAll() ? " union all " : " union ") + wrapUnion(compileSelect(union.builder()));
    }

    /**
     * Wraps one part of a union. The base grammar writes {@code (sql)}, Laravel's; SQLite needs
     * {@code select * from (sql)} instead, because it refuses parentheses around a select.
     *
     * @param sql the part's SQL, never {@code null}
     * @return the wrapped SQL, never {@code null}
     */
    protected String wrapUnion(String sql) {
        return "(" + sql + ")";
    }

    // ---- insert from a select ----

    /**
     * Compiles an insert of the rows a subquery returns.
     *
     * @param query the query naming the target table, never {@code null}
     * @param columns the target columns, never {@code null}; empty or {@code ["*"]} leaves the
     *     column list out
     * @param sql the subquery's SQL, never {@code null}
     * @return {@code insert into "t" ("a") select ...}, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public String compileInsertUsing(Builder query, List<String> columns, String sql) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(sql, "sql");
        String table = wrapTable(query.getFrom());
        if (columns.isEmpty() || columns.equals(Collections.singletonList("*"))) {
            return "insert into " + table + " " + sql;
        }
        return "insert into " + table + " (" + columnize(columns) + ") " + sql;
    }

    /**
     * Compiles an insert of the rows a subquery returns that skips rows breaking a unique or
     * primary key constraint. The base grammar has no such SQL.
     *
     * @param query the query naming the target table, never {@code null}
     * @param columns the target columns, as for {@link QueryGrammar#compileInsertUsing}
     * @param sql the subquery's SQL, never {@code null}
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException always, in the base grammar
     */
    public String compileInsertOrIgnoreUsing(Builder query, List<String> columns, String sql) {
        throw unsupported("Inserting while ignoring errors");
    }

    // ---- update and delete with joins ----

    /**
     * Compiles an update of a query with joins. Laravel's base grammar writes
     * {@code update t inner join ... set ...}, which neither SQLite nor PostgreSQL accept, and
     * which would need the join bindings before the set values; here the base grammar refuses it
     * and a database grammar rewrites the query, keeping the order of
     * {@link QueryGrammar#prepareBindingsForUpdate}: set values, then join, then where.
     *
     * @param query the query, never {@code null}
     * @param table the wrapped table, never {@code null}
     * @param columns the compiled {@code set} list, never {@code null}
     * @param where the compiled where clause, never {@code null}, may be empty
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException always, in the base grammar
     */
    protected String compileUpdateWithJoins(Builder query, String table, String columns, String where) {
        throw unsupported("Update with joins");
    }

    /**
     * Compiles a delete of a query with joins; refused by the base grammar, as for
     * {@link QueryGrammar#compileUpdate}.
     *
     * @param query the query, never {@code null}
     * @param table the wrapped table, never {@code null}
     * @param where the compiled where clause, never {@code null}, may be empty
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException always, in the base grammar
     */
    protected String compileDeleteWithJoins(Builder query, String table, String where) {
        throw unsupported("Delete with joins");
    }
}

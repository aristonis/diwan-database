package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Expression;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
            String tableAndNested = join.joins.isEmpty()
                    ? table
                    : "(" + table + " " + compileJoins(query, join.joins) + ")";
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
     * @throws IllegalArgumentException if the part has a row lock
     */
    protected String compileUnion(Union union) {
        if (union.builder().getLock().isPresent()) {
            throw new IllegalArgumentException("A union part cannot carry a row lock");
        }
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

    // ---- row locks ----

    /**
     * Compiles the row lock, written last in a select. The base grammar writes nothing for
     * {@link LockStrength#UPDATE} and {@link LockStrength#SHARE} with {@link LockWait#WAIT}, because
     * its database has no row locks, and refuses every other combination; a grammar for a database
     * with row locks overrides it.
     *
     * @param query the query, never {@code null}
     * @param lock the lock, never {@code null}
     * @return the SQL, an empty string when nothing is written, never {@code null}
     * @throws IllegalArgumentException if the query has unions
     * @throws UnsupportedOperationException if the strength or wait cannot be written
     */
    protected String compileLock(Builder query, RowLock lock) {
        checkLockable(query);
        if (lock.strength() == LockStrength.NO_KEY_UPDATE || lock.strength() == LockStrength.KEY_SHARE) {
            throw unsupported("The " + lock.strength() + " row lock");
        }
        if (lock.timeoutMillis().isPresent()) {
            throw unsupported("The timed row lock wait");
        }
        if (lock.lockWait() != LockWait.WAIT) {
            throw unsupported("The " + lock.lockWait() + " row lock wait");
        }
        return "";
    }

    /**
     * Refuses a row lock on a query with unions, which PostgreSQL rejects.
     *
     * @param query the query, never {@code null}
     * @throws IllegalArgumentException if the query has unions
     */
    protected final void checkLockable(Builder query) {
        if (!query.unions.isEmpty()) {
            throw new IllegalArgumentException("A union cannot carry a row lock");
        }
    }

    /**
     * Tells whether a row lock is released as soon as the statement ends, so a locked select needs
     * a transaction to mean anything. False in the base grammar; PostgreSQL returns true.
     *
     * @return {@code true} if a locked select outside a transaction is refused
     */
    protected boolean locksNeedTransaction() {
        return false;
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

    // ---- insert or ignore and upsert ----

    /**
     * Compiles an insert of rows that skips a row breaking a unique or primary key constraint.
     * The base grammar has no such SQL; a database grammar writes the insert of
     * {@link QueryGrammar#compileInsert} in its own form.
     *
     * @param query the query naming the target table, never {@code null}
     * @param rows the rows, never {@code null} or empty, as for {@link QueryGrammar#compileInsert}
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException always, in the base grammar
     */
    protected String compileInsertOrIgnore(Builder query, List<? extends Map<String, ?>> rows) {
        throw unsupported("Inserting while ignoring errors");
    }

    /**
     * Compiles an insert that updates the row already there when a unique index decides a
     * conflict. The base grammar has no such SQL.
     *
     * @param query the query naming the target table, never {@code null}
     * @param rows the rows, never {@code null} or empty, none without columns
     * @param uniqueBy the columns of the unique index, never {@code null} or empty
     * @param excluded the columns to take from the refused row, never {@code null}
     * @param values the columns to set to a given value, never {@code null}; with {@code excluded}
     *     both empty, a conflicting row is left as it is
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException always, in the base grammar
     */
    protected String compileUpsert(Builder query, List<? extends Map<String, ?>> rows, List<String> uniqueBy,
            List<String> excluded, Map<String, ?> values) {
        throw unsupported("Upsert");
    }

    /**
     * Writes {@code on conflict (unique columns) do update set ...}, or {@code do nothing} when
     * there is nothing to set, for a database grammar to append to an insert.
     *
     * @param uniqueBy the columns of the unique index, never {@code null} or empty
     * @param excluded the columns to take from the refused row, written
     *     {@code "c" = "excluded"."c"}, never {@code null}
     * @param values the columns to set to a given value, never {@code null}; an {@link Expression}
     *     is written as given, anything else as {@code ?}
     * @return the clause, never {@code null}
     */
    protected final String compileOnConflict(List<String> uniqueBy, List<String> excluded, Map<String, ?> values) {
        return UpsertGrammar.onConflict(this, uniqueBy, excluded, values);
    }

    /**
     * Checks that the database can run an upsert, before any SQL is compiled. The base grammar
     * accepts every version; a grammar for a database whose older versions lack the SQL refuses
     * them here.
     *
     * @param databaseVersion the version the driver reports, never {@code null}
     * @throws UnsupportedOperationException if the version is too old, or cannot be read
     */
    protected void checkUpsert(String databaseVersion) {
        // Every version is accepted by default.
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

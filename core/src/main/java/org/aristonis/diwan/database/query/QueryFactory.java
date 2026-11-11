package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.Connection;

/**
 * Starts queries on one connection, with the grammar and processor of its database.
 *
 * <pre>{@code
 * QueryFactory db = SQLiteQuery.of(connection);
 * List<Map<String, Object>> rows = db.table("users").where("active", true).get();
 * }</pre>
 *
 * <p>The core {@link Connection} cannot start a query itself, because it never knows which
 * database module's grammar to use; the database module's shortcut, or {@link #of}, ties the three
 * together instead.
 *
 * <p>Instances are immutable and safe to share between threads; the builders they return are not.
 */
public final class QueryFactory {

    private final Connection connection;
    private final QueryGrammar grammar;
    private final Processor processor;

    private QueryFactory(Connection connection, QueryGrammar grammar, Processor processor) {
        this.connection = connection;
        this.grammar = grammar;
        this.processor = processor;
    }

    /**
     * Creates a factory.
     *
     * @param connection the connection to run on, never {@code null}; inside a transaction or
     *     pretend callback, pass the connection the callback was given
     * @param grammar the grammar of the connection's database, never {@code null}, built with the
     *     connection's table prefix
     * @param processor the processor of the connection's database, never {@code null}
     * @return the factory, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the grammar's table prefix is not the connection's
     */
    public static QueryFactory of(Connection connection, QueryGrammar grammar, Processor processor) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(grammar, "grammar");
        Objects.requireNonNull(processor, "processor");
        if (!grammar.getTablePrefix().equals(connection.getTablePrefix())) {
            throw new IllegalArgumentException("The grammar's table prefix does not match the connection's");
        }
        return new QueryFactory(connection, grammar, processor);
    }

    /**
     * Starts a query on a table.
     *
     * @param table the table name, never {@code null}, optionally with an alias: {@code "users as u"};
     *     the connection's table prefix is added when compiling
     * @return a new builder, never {@code null}
     * @throws NullPointerException if {@code table} is {@code null}
     */
    public Builder table(String table) {
        return query().from(table);
    }

    /**
     * Starts a query on a table with an alias.
     *
     * @param table the table name, never {@code null}
     * @param as the alias, never {@code null}
     * @return a new builder, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder table(String table, String as) {
        return query().from(table, as);
    }

    /**
     * Starts a query without a table, for example for {@code selectRaw("1")}.
     *
     * @return a new builder, never {@code null}
     */
    public Builder query() {
        return new Builder(connection, grammar, processor);
    }

    /**
     * Empties several tables, without cascade.
     *
     * @param tables the table names, never {@code null}, none {@code null} or blank, optionally
     *     {@code schema.table}; empty runs nothing
     * @throws NullPointerException if {@code tables} is {@code null}
     * @throws IllegalArgumentException if a name is {@code null} or blank
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a statement
     * @see #truncate(List, boolean)
     */
    public void truncate(List<String> tables) {
        truncate(tables, false);
    }

    /**
     * Empties several tables in one call. When the grammar needs several statements, they run in
     * one transaction, unless the connection is already inside one.
     *
     * <p>PostgreSQL runs one {@code truncate} that restarts identities. SQLite defers foreign-key
     * checks to the commit, so the order is irrelevant, but a table outside the list that still
     * refers to an emptied one makes the commit fail and undoes everything, unless {@code cascade}
     * is set: SQLite then empties the referring tables too, as PostgreSQL does.
     *
     * @param tables the table names, never {@code null}, none {@code null} or blank, optionally
     *     {@code schema.table}; empty runs nothing
     * @param cascade whether tables that refer to these are emptied too, where the database can
     * @throws NullPointerException if {@code tables} is {@code null}
     * @throws IllegalArgumentException if a name is {@code null} or blank
     * @throws UnsupportedOperationException if {@code cascade} is {@code true} and the grammar cannot
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a statement;
     *     inside the transaction, every statement is then undone
     */
    public void truncate(List<String> tables, boolean cascade) {
        List<String> names = checkedNames(tables, "truncate");
        if (names.isEmpty()) {
            return;
        }
        Map<String, List<Object>> statements = grammar.compileTruncateMany(names, cascade, connection);
        grammar.runTruncate(connection, names, statements);
    }

    /**
     * Sets the identity and serial counters of tables back, so the next insert gets the id it would
     * get had no row been added since the data now committed: the start value of an empty table, or
     * the value after the highest committed id. A test that rolls its rows back then sees the same
     * ids each time.
     *
     * <p>Only PostgreSQL sends anything, and needs version 10 or later: SQLite's counter rolls back
     * with the rows already. A sequence that no column owns, such as one created on its own and used
     * as a default, is left alone. PostgreSQL never rolls a counter change back, so the reset stays
     * even when the surrounding transaction is undone, and a counter moved past rows that are later
     * rolled back stays past them.
     *
     * <p>Meant for tests, with nothing else writing to the tables: the highest id is read first and
     * the counter set after, so a row another session adds in between can get an id handed out
     * again. Rows of the caller's own open transaction count as committed here, so call it outside
     * a transaction, as {@code RefreshDatabase} does.
     *
     * @param tables the table names, never {@code null}, none {@code null} or blank, optionally
     *     {@code schema.table}; empty runs nothing
     * @throws NullPointerException if {@code tables} is {@code null}
     * @throws IllegalArgumentException if a name is {@code null} or blank
     * @throws UnsupportedOperationException if the database is PostgreSQL older than version 10
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses a
     *     statement, for example because a table does not exist, or because the next id would pass
     *     the largest value the sequence allows
     */
    public void restartIdentities(List<String> tables) {
        List<String> names = checkedNames(tables, "restart");
        if (names.isEmpty()) {
            return;
        }
        grammar.restartIdentities(connection, names);
    }

    private static List<String> checkedNames(List<String> tables, String what) {
        List<String> names = new ArrayList<>(Objects.requireNonNull(tables, "tables"));
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("A table name to " + what + " must not be null or blank");
            }
        }
        return names;
    }


    /**
     * Returns a factory with the same grammar and processor that runs on another connection of the
     * same database, such as the connection a transaction callback receives.
     *
     * <p>Code inside {@code connection.transaction(tx -> ...)} must run on {@code tx}; this lets it
     * keep using the factory it already has without naming the database's module.
     *
     * @param other the connection to run on, never {@code null}
     * @return a factory bound to {@code other}, never {@code null}
     * @throws NullPointerException if {@code other} is {@code null}
     * @throws IllegalArgumentException if {@code other} talks to a different kind of database or uses
     *     a different table prefix
     */
    public QueryFactory on(Connection other) {
        Objects.requireNonNull(other, "other");
        requireSameDatabase(connection, other);
        return of(other, grammar, processor);
    }

    private static void requireSameDatabase(Connection current, Connection other) {
        String expected = current.getDriverInfo().databaseName();
        String actual = other.getDriverInfo().databaseName();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("The connection is to " + actual + ", but this factory writes SQL for "
                    + expected);
        }
    }

    /**
     * Returns the connection.
     *
     * @return the connection, never {@code null}
     */
    public Connection connection() {
        return connection;
    }

    /**
     * Returns the grammar.
     *
     * @return the grammar, never {@code null}
     */
    public QueryGrammar grammar() {
        return grammar;
    }

    /**
     * Returns the processor.
     *
     * @return the processor, never {@code null}
     */
    public Processor processor() {
        return processor;
    }
}

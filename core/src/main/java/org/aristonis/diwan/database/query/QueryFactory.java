package org.aristonis.diwan.database.query;

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

package org.aristonis.diwan.database.eloquent;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;

/** One in-memory SQLite database whose tables a test builds with plain SQL, for model tests. */
final class SqliteModelDb implements AutoCloseable {

    /** Hands out the one JDBC connection and counts how often it is taken and given back. */
    private static final class CountingProvider implements JdbcConnectionProvider {
        private final java.sql.Connection jdbc;
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();

        CountingProvider(java.sql.Connection jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public java.sql.Connection acquire() {
            acquired.incrementAndGet();
            return jdbc;
        }

        @Override
        public void release(java.sql.Connection connection) {
            released.incrementAndGet();
        }
    }

    private final java.sql.Connection jdbc;
    private final CountingProvider provider;
    final Connection connection;
    final QueryFactory db;

    private SqliteModelDb(String tablePrefix) throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        provider = new CountingProvider(jdbc);
        connection = Connection.builder().name("sqlite").connectionProvider(provider).tablePrefix(tablePrefix)
                .build();
        db = SQLiteQuery.of(connection);
    }

    /** Opens a database whose connection has no table prefix. */
    static SqliteModelDb open() throws SQLException {
        return new SqliteModelDb("");
    }

    /** Opens a database whose connection prefixes every table name. */
    static SqliteModelDb openWithPrefix(String tablePrefix) throws SQLException {
        return new SqliteModelDb(tablePrefix);
    }

    /** Runs one SQL statement, such as a create table. */
    void sql(String statement) {
        connection.statement(statement);
    }

    /** Reads rows with plain SQL, bypassing every model. */
    List<Map<String, Object>> rows(String select, Object... bindings) {
        return connection.select(select, bindings);
    }

    /** Reads the one value of the first row of a plain SQL select. */
    Object scalar(String select, Object... bindings) {
        List<Map<String, Object>> rows = rows(select, bindings);
        return rows.isEmpty() ? null : rows.get(0).values().iterator().next();
    }

    /** Returns how many JDBC connections are taken and not yet given back. */
    int heldConnections() {
        return provider.acquired.get() - provider.released.get();
    }

    @Override
    public void close() throws SQLException {
        jdbc.close();
    }
}

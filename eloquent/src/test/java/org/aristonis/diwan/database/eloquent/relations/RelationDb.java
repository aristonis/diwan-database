package org.aristonis.diwan.database.eloquent.relations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;

/**
 * An in-memory SQLite database with the relation test tables and a log of every select the
 * logged factory runs. Seeding goes through an unlogged factory on the same connection.
 */
final class RelationDb implements AutoCloseable {

    private static final List<String> SELECTS = Arrays.asList("select", "selectOne", "scalar", "cursor");

    /** One logged select: its SQL and bindings. */
    record Query(String sql, List<Object> bindings) {
    }

    private final java.sql.Connection jdbc;
    private final QueryFactory raw;
    private final List<Query> log = Collections.synchronizedList(new ArrayList<>());

    /** The logged factory models are bound to. */
    final QueryFactory db;

    /** The unlogged connection, for tests that add their own tables. */
    final Connection real;

    RelationDb() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        real = Connection.builder().name("sqlite").connectionProvider(new Single(jdbc)).build();
        raw = SQLiteQuery.of(real);
        db = SQLiteQuery.of(logged(real));
        createTables(real);
        RelationModels.register();
    }

    private Connection logged(Connection real) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (SELECTS.contains(method.getName()) && args != null && args[0] instanceof String) {
                        log.add(new Query((String) args[0], bindingsOf(args[1])));
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static List<Object> bindingsOf(Object argument) {
        if (argument instanceof List) {
            return new ArrayList<>((List<Object>) argument);
        }
        return argument == null ? Collections.emptyList() : Arrays.asList((Object[]) argument);
    }

    private static void createTables(Connection real) {
        SQLiteSchema.of(real).create("users", t -> {
            t.id();
            t.string("name");
        });
        SQLiteSchema.of(real).create("posts", t -> {
            t.id();
            t.integer("user_id").nullable();
            t.string("title");
        });
        SQLiteSchema.of(real).create("comments", t -> {
            t.id();
            t.integer("post_id").nullable();
            t.string("body");
        });
        SQLiteSchema.of(real).create("profiles", t -> {
            t.id();
            t.integer("user_id").nullable();
            t.string("bio");
        });
    }

    /**
     * Inserts a row without logging it.
     *
     * @param table the table
     * @param row the row
     */
    void seed(String table, Map<String, ?> row) {
        raw.table(table).insert(row);
    }

    /** Forgets the logged selects. */
    void clearLog() {
        log.clear();
    }

    /**
     * Returns the logged selects since the last clear.
     *
     * @return a copy of the log
     */
    List<Query> queries() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    /**
     * Returns the SQL of the logged selects since the last clear.
     *
     * @return the SQL texts, in order
     */
    List<String> sql() {
        List<String> texts = new ArrayList<>();
        for (Query query : queries()) {
            texts.add(query.sql());
        }
        return texts;
    }

    @Override
    public void close() throws SQLException {
        jdbc.close();
    }

    /** Hands out the one JDBC connection; the database closes it at the end. */
    private static final class Single implements JdbcConnectionProvider {
        private final java.sql.Connection connection;

        Single(java.sql.Connection connection) {
            this.connection = connection;
        }

        @Override
        public java.sql.Connection acquire() {
            return connection;
        }

        @Override
        public void release(java.sql.Connection released) {
            // Closed by RelationDb.close().
        }
    }
}

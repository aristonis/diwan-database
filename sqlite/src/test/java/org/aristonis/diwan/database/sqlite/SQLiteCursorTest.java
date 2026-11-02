package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.driver.RowCursor;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteCursorTest {

    private static final int ROWS = 20;

    @TempDir
    Path folder;

    private final List<PreparedStatement> statements = new ArrayList<>();
    private final Semaphore pool = new Semaphore(1);
    private SQLiteDataSource dataSource;
    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void seed() {
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("cursor.db"));
        connection = Connection.builder().name("sqlite").connectionProvider(new PoolOfOne()).build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("items", t -> {
            t.id();
            t.string("name");
        });
        for (int i = 1; i <= ROWS; i++) {
            db.table("items").insert(java.util.Collections.singletonMap("name", "item" + i));
        }
    }

    /** One connection; a second acquire while it is out fails after a short wait. */
    private final class PoolOfOne implements JdbcConnectionProvider {

        @Override
        public java.sql.Connection acquire() throws SQLException {
            try {
                if (!pool.tryAcquire(2, TimeUnit.SECONDS)) {
                    throw new SQLException("pool exhausted", "08004");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted", "08004");
            }
            return spy(dataSource.getConnection());
        }

        @Override
        public void release(java.sql.Connection connection) throws SQLException {
            try {
                connection.close();
            } finally {
                pool.release();
            }
        }
    }

    private java.sql.Connection spy(java.sql.Connection target) {
        return (java.sql.Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (result instanceof PreparedStatement) {
                        statements.add((PreparedStatement) result);
                    }
                    return result;
                });
    }

    @Test
    void streamsWithTheStatementStillOpenAfterTheFirstRow() throws SQLException {
        try (RowCursor cursor = db.table("items").orderBy("id").cursor()) {
            Map<String, Object> first = cursor.next();

            assertEquals("item1", first.get("name"));
            assertFalse(statements.get(statements.size() - 1).isClosed());
        }
        assertTrue(statements.get(statements.size() - 1).isClosed());
    }

    @Test
    void fiftyCursorsOnAPoolOfOneLeakNothing() {
        for (int run = 0; run < 50; run++) {
            try (RowCursor cursor = db.table("items").orderBy("id").cursor()) {
                cursor.next();
            }
            int count = 0;
            for (Map<String, Object> ignored : db.table("items").cursor()) {
                count++;
            }
            assertEquals(ROWS, count);
        }
        assertEquals(1, pool.availablePermits());
    }

    @Test
    void readsInsideATransactionWithoutEndingIt() {
        connection.transaction(tx -> {
            tx.insert("insert into items (name) values (?)", "inside");
            int count = 0;
            try (RowCursor cursor = tx.cursor("select * from items")) {
                for (Map<String, Object> ignored : cursor) {
                    count++;
                }
            }
            assertEquals(ROWS + 1, count);
            return null;
        });

        assertEquals(ROWS + 1, db.table("items").count());
        assertEquals(1, pool.availablePermits());
    }

    @Test
    void aBadQueryFailsAtOpenAndReleasesTheConnection() {
        assertThrows(QueryException.class, () -> connection.cursor("select * from missing"));

        assertEquals(1, pool.availablePermits());
    }
}

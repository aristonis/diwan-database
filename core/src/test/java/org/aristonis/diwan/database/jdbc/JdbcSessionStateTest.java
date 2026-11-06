package org.aristonis.diwan.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** What a JDBC session leaves alone on a pooled connection, and what it does with a connection gone stale. */
class JdbcSessionStateTest {

    @TempDir
    Path folder;

    private SQLiteDataSource sqlite() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + folder.resolve("state.db"));
        return source;
    }

    /** Hands out SQLite connections as proxies that record every call and can fail the first statement. */
    private static final class ProxyDataSource implements DataSource {
        private final DataSource target;
        private final boolean isFirstConnectionStale;
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger open = new AtomicInteger();
        private final AtomicInteger handed = new AtomicInteger();

        ProxyDataSource(DataSource target, boolean isFirstConnectionStale) {
            this.target = target;
            this.isFirstConnectionStale = isFirstConnectionStale;
        }

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            java.sql.Connection real = target.getConnection();
            boolean isStale = isFirstConnectionStale && handed.incrementAndGet() == 1;
            open.incrementAndGet();
            AtomicBoolean isReleased = new AtomicBoolean();
            return (java.sql.Connection) Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                    new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                        calls.add(method.getName());
                        if (isStale && method.getName().equals("prepareStatement")) {
                            throw new SQLException("connection is closed", "08003");
                        }
                        boolean isEnd = method.getName().equals("close") || method.getName().equals("abort");
                        if (isEnd && isReleased.compareAndSet(false, true)) {
                            open.decrementAndGet();
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }

    @Test
    void commitWithNoOpenTransactionIsRefusedAndTheSessionStaysUsable() {
        DriverSession session = new JdbcDriver(new DataSourceConnectionProvider(sqlite())).open();

        assertThrows(DriverException.class, session::commit);
        assertEquals(1, ((Number) session.query("select 1 as one", Collections.emptyList()).get(0).get("one")).intValue());
        session.close();
    }

    @Test
    void rollbackWithNoOpenTransactionIsRefusedAndTheSessionStaysUsable() {
        DriverSession session = new JdbcDriver(new DataSourceConnectionProvider(sqlite())).open();

        assertThrows(DriverException.class, session::rollback);
        assertEquals(1, ((Number) session.query("select 1 as one", Collections.emptyList()).get(0).get("one")).intValue());
        session.close();
    }

    @Test
    void neverChangesTheReadOnlyFlagCatalogOrIsolationOfAPooledConnection() {
        ProxyDataSource pool = new ProxyDataSource(sqlite(), false);
        Connection db = Connection.builder().name("pool").dataSource(pool).build();

        db.statement("create table t (id integer)");
        db.transaction(tx -> tx.insert("insert into t values (1)"));
        db.select("select * from t");

        assertFalse(pool.calls.contains("setReadOnly"));
        assertFalse(pool.calls.contains("setCatalog"));
        assertFalse(pool.calls.contains("setSchema"));
        assertFalse(pool.calls.contains("setTransactionIsolation"));
        assertEquals(0, pool.open.get());
    }

    @Test
    void aStaleConnectionFromThePoolReachesTheCallerOnceAndTheNextCallWorks() {
        ProxyDataSource pool = new ProxyDataSource(sqlite(), true);
        Connection db = Connection.builder().name("pool").dataSource(pool).build();

        assertThrows(QueryException.class, () -> db.select("select 1 as one"));
        List<?> rows = db.select("select 1 as one");

        assertEquals(1, rows.size());
        assertTrue(pool.calls.contains("prepareStatement"));
        assertEquals(0, pool.open.get());
    }
}

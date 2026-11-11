package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.Migrator;
import org.junit.jupiter.api.Test;

/** What the PostgreSQL migration lock reports when its session is short or lost. */
class PostgresMigrationLockTest {

    private static final String POOL_HINT =
            "The migration lock holds one session of this data source; if this is a pool, it may need at least two";
    private static final String ADVISORY_PID = "select pid from pg_locks where locktype = 'advisory' and granted"
            + " and database = (select oid from pg_database where datname = current_database())";
    private static final long SHORT_WAIT_MILLIS = 300;
    private static final int MAX_NAME_SEARCH = 1000;

    /** A data source that has one session to give: a second open connection is refused, as a full pool does. */
    private static final class PoolOfOne implements DataSource {
        private final DataSource real;
        private final AtomicInteger open = new AtomicInteger();

        PoolOfOne(DataSource real) {
            this.real = real;
        }

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            if (!open.compareAndSet(0, 1)) {
                throw new SQLTransientConnectionException("Connection is not available, request timed out");
            }
            java.sql.Connection connection;
            try {
                connection = real.getConnection();
            } catch (SQLException | RuntimeException e) {
                open.set(0);
                throw e;
            }
            InvocationHandler handler = (proxy, method, args) -> {
                try {
                    Object result = method.invoke(connection, args);
                    if ("close".equals(method.getName())) {
                        open.set(0);
                    }
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (java.sql.Connection) Proxy.newProxyInstance(
                    PostgresMigrationLockTest.class.getClassLoader(), new Class<?>[] {java.sql.Connection.class},
                    handler);
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
            throw new SQLException("Not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }

    private static final class Noop extends Migration {
        @Override
        public String name() {
            return "2026_01_noop";
        }

        @Override
        public void up(org.aristonis.diwan.database.schema.Schema schema) {
            schema.create("noop", t -> t.id());
        }

        @Override
        public void down(org.aristonis.diwan.database.schema.Schema schema) {
            schema.drop("noop");
        }
    }

    private static boolean hasHint(Throwable failure) {
        return Arrays.stream(failure.getSuppressed())
                .anyMatch(s -> s instanceof IllegalStateException && POOL_HINT.equals(s.getMessage()));
    }

    @Test
    void aPoolOfOneFailsWithANoteThatThePoolNeedsTwoSessions() {
        String database = PostgresServer.newDatabaseName();
        PoolOfOne pool = new PoolOfOne(PostgresServer.dataSource(database));
        Connection db = Connection.builder().name("pgsql").dataSource(pool).build();
        Migrator migrator = Migrator.builder(PostgresSchema.of(db))
                .migrations(Arrays.asList(new Noop())).lock(new PostgresMigrationLock(pool)).build();

        RuntimeException failure = assertThrows(RuntimeException.class, migrator::run);

        assertTrue(hasHint(failure), "suppressed: " + Arrays.toString(failure.getSuppressed()));
    }

    @Test
    void aFailureWithoutAConnectionProblemGetsNoPoolHint() {
        String database = PostgresServer.newDatabaseName();
        PostgresMigrationLock lock = new PostgresMigrationLock(PostgresServer.dataSource(database));
        lock.acquire();
        try {
            RuntimeException failure = new RuntimeException("boom");

            lock.explain(failure);

            assertEquals(0, failure.getSuppressed().length);
        } finally {
            lock.release();
        }
    }

    @Test
    void aLockThatIsNotHeldAddsNothingToAConnectionFailure() {
        String database = PostgresServer.newDatabaseName();
        PostgresMigrationLock lock = new PostgresMigrationLock(PostgresServer.dataSource(database));
        RuntimeException failure = new RuntimeException("wrapped", new SQLTransientConnectionException("full"));

        lock.explain(failure);

        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void aKilledSessionIsReportedOnRelease() {
        Connection db = PostgresServer.newDatabase();
        String database = String.valueOf(db.scalar("select current_database()").get());
        PostgresMigrationLock lock = new PostgresMigrationLock(PostgresServer.dataSource(database), "killed");
        lock.acquire();
        Object pid = db.scalar(ADVISORY_PID).get();
        db.scalar("select pg_terminate_backend(" + pid + ")");

        IllegalStateException error = assertThrows(IllegalStateException.class, lock::release);

        // The ended session cannot answer the unlock, so the report says the lock could not be released.
        assertTrue(error.getMessage().contains("released"), error.getMessage());
        lock.release();
    }

    @Test
    void namesTheHolderOfANegativeKey() {
        Connection db = PostgresServer.newDatabase();
        String database = String.valueOf(db.scalar("select current_database()").get());
        String name = negativeKeyName();
        PostgresMigrationLock holder = new PostgresMigrationLock(PostgresServer.dataSource(database), name);
        PostgresMigrationLock waiter = new PostgresMigrationLock(PostgresServer.dataSource(database), name,
                SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        holder.acquire();
        try {
            String pid = String.valueOf(db.scalar(ADVISORY_PID).get());

            IllegalStateException error = assertThrows(IllegalStateException.class, waiter::acquire);

            assertTrue(error.getMessage().contains("PostgreSQL pid " + pid + ")"), error.getMessage());
        } finally {
            holder.release();
        }
    }

    private static String negativeKeyName() {
        for (int i = 0; i < MAX_NAME_SEARCH; i++) {
            String name = "negative-" + i;
            if (PostgresMigrationLock.keyOf(name) < 0) {
                return name;
            }
        }
        throw new AssertionError("No name with a negative key in " + MAX_NAME_SEARCH + " tries");
    }
}

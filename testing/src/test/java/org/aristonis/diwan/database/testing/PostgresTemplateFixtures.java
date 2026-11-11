package org.aristonis.diwan.database.testing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.pgsql.PostgresConnector;
import org.aristonis.diwan.database.schema.Schema;
import org.postgresql.ds.PGSimpleDataSource;

/** Managers, migrations and data sources shared by the PostgreSQL template-mode tests. */
final class PostgresTemplateFixtures {

    /** How often each probe migration, by name, ran in this JVM. */
    static final Map<String, AtomicInteger> BUILDS = new ConcurrentHashMap<>();

    private static final long SLOT_WAIT_SECONDS = 5;

    private PostgresTemplateFixtures() {
    }

    /** Opens a manager on a database of the throwaway server as its superuser. */
    static DatabaseManager manager(String database) {
        return manager(PostgresServer.dataSource("postgres"), database, "postgres");
    }

    /** Opens a manager on a database of the server behind {@code server}. */
    static DatabaseManager manager(PGSimpleDataSource server, String database, String user) {
        ConnectionConfig connection = ConnectionConfig.builder("main").driver("pgsql")
                .host(server.getServerNames()[0]).port(server.getPortNumbers()[0]).database(database)
                .username(user).build();
        DatabaseConfig config = DatabaseConfig.builder().defaultConnection("main").connection(connection).build();
        return DatabaseManager.builder().config(config).connector(new PostgresConnector()).build();
    }

    /** Opens a connection to the {@code postgres} database as the superuser, for set-up and checks. */
    static Connection admin() {
        return Connection.builder().name("admin").dataSource(PostgresServer.dataSource("postgres")).build();
    }

    /** Tells whether a database of that name exists. */
    static boolean exists(String database) {
        return !admin().select("select 1 from pg_database where datname = ?", database).isEmpty();
    }

    /** Counts the databases whose name matches a LIKE pattern. */
    static long count(String pattern) {
        return ((Number) admin().scalar("select count(*) from pg_database where datname like ?", pattern)
                .orElse(0L)).longValue();
    }

    /** Drops every database whose name matches a LIKE pattern, closing sessions on it. */
    static void dropAll(String pattern) {
        Connection admin = admin();
        for (Map<String, Object> row : admin.select("select datname from pg_database where datname like ?", pattern)) {
            admin.statement("drop database if exists \"" + row.get("datname") + "\" with (force)");
        }
    }

    /** Returns a migration list that no earlier run used, so its template does not exist yet. */
    static List<Migration> uniqueMigrations() {
        return List.of(new Probe("2026_11_11_" + UUID.randomUUID().toString().replace('-', '_')));
    }

    /** Creates an {@code items} table and counts its runs by name. */
    static final class Probe extends Migration {
        private final String name;

        Probe(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void up(Schema schema) {
            BUILDS.computeIfAbsent(name, key -> new AtomicInteger()).incrementAndGet();
            schema.create("items", table -> {
                table.id();
                table.string("label");
            });
        }
    }

    /** A data source that fails every prepared statement whose text matches, as a refused drop. */
    static final class FailingStatements extends PGSimpleDataSource {
        private static final long serialVersionUID = 1L;

        private final transient Predicate<String> isFailing;

        FailingStatements(PGSimpleDataSource server, Predicate<String> isFailing) {
            copyOf(this, server);
            this.isFailing = isFailing;
        }

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            java.sql.Connection real = super.getConnection();
            return (java.sql.Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String
                                && isFailing.test((String) args[0])) {
                            throw new SQLException("statement refused by the test", "42501");
                        }
                        return invoke(real, method, args);
                    });
        }
    }

    /**
     * A data source that lends one connection at a time, as a pool of one slot does. A second
     * request waits, then fails, so a run that wants two connections is caught.
     */
    static final class OneSlotPool extends PGSimpleDataSource {
        private static final long serialVersionUID = 1L;

        private final transient Semaphore slot = new Semaphore(1);

        OneSlotPool(PGSimpleDataSource server) {
            copyOf(this, server);
        }

        /** Returns how many slots are free: one when nothing is lent. */
        int freeSlots() {
            return slot.availablePermits();
        }

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            try {
                if (!slot.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new SQLException("The one-slot pool is exhausted");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for the pool", e);
            }
            java.sql.Connection real;
            try {
                real = super.getConnection();
            } catch (SQLException | RuntimeException e) {
                slot.release();
                throw e;
            }
            AtomicBoolean isReturned = new AtomicBoolean();
            return (java.sql.Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close") && isReturned.compareAndSet(false, true)) {
                            try {
                                return invoke(real, method, args);
                            } finally {
                                slot.release();
                            }
                        }
                        return invoke(real, method, args);
                    });
        }
    }

    /** Runs one statement with bound values on a JDBC connection a test holds open. */
    static void execute(java.sql.Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.execute();
        }
    }

    private static void copyOf(PGSimpleDataSource target, PGSimpleDataSource server) {
        target.setServerNames(server.getServerNames());
        target.setPortNumbers(server.getPortNumbers());
        target.setDatabaseName(server.getDatabaseName());
        target.setUser(server.getUser());
    }

    private static Object invoke(java.sql.Connection real, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

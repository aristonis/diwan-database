package org.aristonis.diwan.database.pgsql;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.migrations.MigrationLock;

/**
 * Keeps two processes from migrating one PostgreSQL database at the same time, with a session
 * advisory lock.
 *
 * <p>An advisory lock belongs to the database session that took it, so the lock opens its own
 * connection from the data source on {@link #acquire()} and holds it until {@link #release()}; the
 * migrator needs another connection besides, so a pool must allow two. Give it a data source that
 * hands out real sessions: a plain driver data source, or a pool in session mode, never a pooler
 * in transaction or statement mode, where lock and unlock could reach different sessions. If the
 * process dies, PostgreSQL ends its session and frees the lock.
 *
 * <p>The session of the lock is set to no {@code lock_timeout} while it holds the lock, so a
 * {@code lock_wait} configured on the data source never cuts the migration lock short; the earlier
 * value is put back, as a bound parameter, before the connection is closed. This covers the lock's
 * own session only: the migrations run on the connection of the migrator and keep its settings.
 *
 * <p>The key is made from a name with FNV-1a, so the same name gives the same key in every version
 * of this library; changing it would let an old and a new application migrate at once.
 */
public final class PostgresMigrationLock implements MigrationLock {

    /** The name the lock key is made from when none is given. */
    public static final String DEFAULT_NAME = "diwan-migrations";

    /** How long {@link #acquire()} waits by default, in seconds. */
    public static final long DEFAULT_WAIT_SECONDS = 60;

    private static final long POLL_MILLIS = 200;
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final String POOL_HINT =
            "The migration lock holds one session of this data source; if this is a pool, it may need at least two";

    private final DataSource dataSource;
    private final long key;
    private final long waitMillis;
    private java.sql.Connection held;
    /** The {@code lock_timeout} of the held session before the lock set it to none. */
    private String heldLockTimeout;

    /**
     * Creates a lock with the default name and wait.
     *
     * @param dataSource the data source of the database being migrated, never {@code null}
     * @throws NullPointerException if {@code dataSource} is {@code null}
     */
    public PostgresMigrationLock(DataSource dataSource) {
        this(dataSource, DEFAULT_NAME, DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Creates a lock whose key is made from a name, so different applications sharing one database
     * can use different locks, waiting the default time.
     *
     * @param dataSource the data source of the database being migrated, never {@code null}
     * @param name the lock's name, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public PostgresMigrationLock(DataSource dataSource, String name) {
        this(dataSource, name, DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Creates a lock with a name and a longest wait.
     *
     * @param dataSource the data source of the database being migrated, never {@code null}
     * @param name the lock's name, never {@code null}
     * @param wait how long {@link #acquire()} waits for another process, 0 or more
     * @param unit the unit of {@code wait}, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code wait} is negative
     */
    public PostgresMigrationLock(DataSource dataSource, String name, long wait, TimeUnit unit) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.key = keyOf(Objects.requireNonNull(name, "name"));
        if (wait < 0) {
            throw new IllegalArgumentException("The wait must not be negative");
        }
        this.waitMillis = Objects.requireNonNull(unit, "unit").toMillis(wait);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if this lock is already held, another process still holds it
     *     when the wait runs out (the message names that process), or the lock cannot be taken
     */
    @Override
    public synchronized void acquire() {
        if (held != null) {
            throw new IllegalStateException("The migration lock is already held");
        }
        java.sql.Connection connection = null;
        String savedTimeout = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            keepSessionAlive(connection);
            savedTimeout = clearLockTimeout(connection);
            // nanoTime, so a wall-clock jump neither ends the wait early nor stretches it.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
            while (!ask(connection, "select pg_try_advisory_lock(?)")) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("The migration lock is held by another process (PostgreSQL pid "
                            + holderOrUnknown(connection) + "); waited " + waitMillis + " ms");
                }
                Thread.sleep(POLL_MILLIS);
            }
            held = connection;
            heldLockTimeout = savedTimeout;
        } catch (SQLException e) {
            abandon(connection, savedTimeout);
            throw new IllegalStateException("The migration lock could not be taken", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(connection, savedTimeout);
            throw new IllegalStateException("Interrupted while waiting for the migration lock", e);
        } catch (RuntimeException e) {
            abandon(connection, savedTimeout);
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the session no longer held the lock, or releasing failed;
     *     every advisory lock of the session is then dropped before its connection is closed, so a
     *     pool never gets the lock back with the connection
     */
    @Override
    public synchronized void release() {
        java.sql.Connection connection = held;
        String savedTimeout = heldLockTimeout;
        held = null;
        heldLockTimeout = null;
        if (connection == null) {
            return;
        }
        String problem = null;
        Exception cause = null;
        try {
            if (!ask(connection, "select pg_advisory_unlock(?)")) {
                problem = "The migration lock was no longer held when released; the session may have been ended"
                        + " during the migration";
            }
        } catch (SQLException e) {
            problem = "The migration lock could not be released";
            cause = e;
            unlockAll(connection);
        } finally {
            abandon(connection, savedTimeout);
        }
        if (problem != null) {
            throw new IllegalStateException(problem, cause);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>When this lock holds its session and the failure, or one of its causes, is a lost
     * connection or a refusal of the pool to give out a connection, the likely reason is a pool of
     * one session, which the lock takes for itself; a suppressed {@link IllegalStateException} says
     * so.
     */
    @Override
    public synchronized void explain(RuntimeException failure) {
        if (held == null) {
            return;
        }
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (isConnectionTrouble(cause)) {
                failure.addSuppressed(new IllegalStateException(POOL_HINT));
                return;
            }
            cause = cause.getCause();
        }
    }

    private static boolean isConnectionTrouble(Throwable cause) {
        if (cause instanceof SQLTransientConnectionException) {
            return true;
        }
        return cause instanceof DriverException
                && ((DriverException) cause).kinds().contains(DriverFailureKind.CONNECTION_LOST);
    }

    // Migrations are not bounded by a configured lock_wait: the session waits for no lock timeout.
    private static String clearLockTimeout(java.sql.Connection connection) throws SQLException {
        String saved;
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select current_setting('lock_timeout')")) {
            if (!result.next()) {
                throw new SQLException("current_setting('lock_timeout') returned no row");
            }
            saved = result.getString(1);
        }
        setLockTimeout(connection, "0");
        return saved;
    }

    private static void setLockTimeout(java.sql.Connection connection, String value) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select set_config('lock_timeout', ?, false)")) {
            statement.setString(1, value);
            // execute, not executeQuery: the row only echoes the value, so nothing is read.
            statement.execute();
        }
    }

    // The connection may go back to a pool, so the setting it came with is put back before it is closed.
    private static void abandon(java.sql.Connection connection, String savedTimeout) {
        if (connection != null && savedTimeout != null) {
            try {
                setLockTimeout(connection, savedTimeout);
            } catch (SQLException e) {
                // The session may be gone already; closing the connection is all that is left.
            }
        }
        closeQuietly(connection);
    }

    private boolean ask(java.sql.Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    // A failing lookup must not replace the timeout with a less useful error.
    private String holderOrUnknown(java.sql.Connection connection) {
        try {
            return holder(connection);
        } catch (SQLException e) {
            return "unknown";
        }
    }

    private String holder(java.sql.Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pid from pg_locks where"
                + " locktype = 'advisory' and granted and ((classid::bigint << 32) | objid::bigint) = ? limit 1")) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : "unknown";
            }
        }
    }

    // A server or network that ends idle sessions would free the lock while migrations run.
    private static void keepSessionAlive(java.sql.Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("set idle_session_timeout = 0");
        } catch (SQLException e) {
            // Before PostgreSQL 14 the setting does not exist, and idle sessions are not ended.
        }
    }

    private static void unlockAll(java.sql.Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("select pg_advisory_unlock_all()");
        } catch (SQLException e) {
            // Closing the connection is the last resort; a plain data source ends the session with it.
        }
    }

    // FNV-1a over the name's bytes: stable across runs and JVMs, unlike String.hashCode's 32 bits.
    static long keyOf(String name) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static void closeQuietly(java.sql.Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            // Nothing more can be done; the session ends when the server notices.
        }
    }
}

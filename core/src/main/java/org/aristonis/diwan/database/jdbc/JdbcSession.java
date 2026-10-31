package org.aristonis.diwan.database.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.driver.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.driver.DriverSession;

/**
 * A driver session over one JDBC connection; used by one thread at a time.
 *
 * <p>A session that owns its connection runs it in auto-commit mode outside {@link #begin()}, so
 * work done outside a transaction is committed statement by statement, as on a plain connection.
 * Pools are often configured to hand out connections with auto-commit off; without this, such
 * work would sit in an implicit transaction that the pool rolls back at release (losing it) or,
 * on Oracle, commits at release (committing it unexpectedly). The session switches auto-commit on
 * when it opens and switches it back off when it closes, after rolling back any open transaction,
 * so the pool gets the connection back as it handed it out.
 *
 * <p>A session in an external transaction leaves auto-commit alone and refuses to begin, commit
 * or roll back that transaction; savepoints remain available.
 */
final class JdbcSession implements DriverSession {

    // The name goes to the database verbatim, so it must not be able to carry SQL.
    private static final Pattern SAVEPOINT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,29}");

    private final JdbcConnectionProvider provider;
    private final Connection connection;
    private final boolean isExternal;
    /** The product name used to read vendor codes, or {@code null} if unknown. */
    private final String productName;
    /** Savepoints by name, in creation order. */
    private final Map<String, Savepoint> savepoints = new LinkedHashMap<>();

    /** Whether auto-commit was off at open and was switched on for the session's life. */
    private final boolean shouldRestoreManualCommit;

    private boolean isTransactionOpen;
    /** Whether {@link #begin()} switched auto-commit off for the current transaction. */
    private boolean isAutoCommitSuspended;
    private boolean isClosed;

    private JdbcSession(JdbcConnectionProvider provider, Connection connection, boolean isExternal,
            String productName, boolean shouldRestoreManualCommit) {
        this.provider = provider;
        this.connection = connection;
        this.isExternal = isExternal;
        this.productName = productName;
        this.shouldRestoreManualCommit = shouldRestoreManualCommit;
    }

    /**
     * Opens a session on an acquired connection, releasing it if the session cannot be set up.
     *
     * @param provider the provider the connection came from
     * @param connection the acquired connection
     * @param info the driver's cached description; read from this connection and seeded if empty
     * @return the session
     * @throws DriverException if the connection cannot be inspected or prepared
     */
    static JdbcSession open(JdbcConnectionProvider provider, Connection connection,
            AtomicReference<DriverInfo> info) {
        try {
            boolean isExternal = provider.inExternalTransaction(connection);
            String productName = productName(connection, info);
            boolean shouldRestoreManualCommit = false;
            if (!isExternal && !connection.getAutoCommit()) {
                connection.setAutoCommit(true);
                shouldRestoreManualCommit = true;
            }
            return new JdbcSession(provider, connection, isExternal, productName, shouldRestoreManualCommit);
        } catch (SQLException | RuntimeException e) {
            try {
                provider.release(connection);
            } catch (SQLException | RuntimeException releaseFailure) {
                e.addSuppressed(releaseFailure);
            }
            if (e instanceof SQLException) {
                throw JdbcFailures.translate((SQLException) e, null);
            }
            throw (RuntimeException) e;
        }
    }

    private static String productName(Connection connection, AtomicReference<DriverInfo> info)
            throws SQLException {
        DriverInfo cached = info.get();
        if (cached == null) {
            info.compareAndSet(null, JdbcDriver.describe(connection.getMetaData()));
            cached = info.get();
        }
        return cached.databaseName();
    }

    @Override
    public List<Map<String, Object>> query(String sql, List<Object> bindings) {
        ensureOpen();
        try (PreparedStatement statement = prepare(sql, bindings);
                ResultSet resultSet = statement.executeQuery()) {
            return readRows(resultSet);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public int update(String sql, List<Object> bindings) {
        ensureOpen();
        try (PreparedStatement statement = prepare(sql, bindings)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public boolean execute(String sql, List<Object> bindings) {
        ensureOpen();
        try (PreparedStatement statement = prepare(sql, bindings)) {
            return statement.execute();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public void executeUnprepared(String sql) {
        ensureOpen();
        Objects.requireNonNull(sql, "sql");
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public boolean inExternalTransaction() {
        return isExternal;
    }

    @Override
    public void begin() {
        ensureOpen();
        ensureOwnTransaction();
        try {
            if (connection.getAutoCommit()) {
                connection.setAutoCommit(false);
                isAutoCommitSuspended = true;
            }
            isTransactionOpen = true;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public void commit() {
        ensureOpen();
        ensureOwnTransaction();
        try {
            connection.commit();
            endTransaction();
            resumeAutoCommit();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public void rollback() {
        ensureOpen();
        ensureOwnTransaction();
        try {
            connection.rollback();
            endTransaction();
            resumeAutoCommit();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public void savepoint(String name) {
        ensureOpen();
        Objects.requireNonNull(name, "name");
        if (!SAVEPOINT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "savepoint name must be 1 to 30 ASCII letters, digits or underscores, not starting with a digit");
        }
        if (savepoints.containsKey(name)) {
            throw new IllegalArgumentException("savepoint already exists: " + name);
        }
        try {
            savepoints.put(name, connection.setSavepoint(name));
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public void rollbackToSavepoint(String name) {
        ensureOpen();
        Savepoint savepoint = requireSavepoint(name);
        try {
            connection.rollback(savepoint);
        } catch (SQLException e) {
            throw failure(e);
        }
        // The rolled-back-to savepoint survives; every later one is gone.
        dropAfter(name, false);
    }

    @Override
    public void releaseSavepoint(String name) {
        ensureOpen();
        Savepoint savepoint = requireSavepoint(name);
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            throw failure(e);
        }
        dropAfter(name, true);
    }

    @Override
    public void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        List<Exception> failures = new ArrayList<>();
        if (isTransactionOpen) {
            try {
                connection.rollback();
            } catch (SQLException | RuntimeException e) {
                failures.add(e);
            }
            endTransaction();
        }
        if (isAutoCommitSuspended || shouldRestoreManualCommit) {
            try {
                // Hand the connection back with the auto-commit mode it was acquired with.
                connection.setAutoCommit(!shouldRestoreManualCommit);
                isAutoCommitSuspended = false;
            } catch (SQLException | RuntimeException e) {
                failures.add(e);
            }
        }
        try {
            provider.release(connection);
        } catch (SQLException | RuntimeException e) {
            failures.add(e);
        }
        if (failures.isEmpty()) {
            return;
        }
        DriverException first = toDriverException(failures.get(0));
        for (int i = 1; i < failures.size(); i++) {
            first.addSuppressed(failures.get(i));
        }
        throw first;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Aborts the JDBC connection first, which pools treat as broken and drop, then
     *     releases it to the provider; no rollback or auto-commit reset is tried on it.
     */
    @Override
    public void discard() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        endTransaction();
        List<Exception> failures = new ArrayList<>();
        try {
            abort(connection);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failures.add(e);
        }
        try {
            provider.release(connection);
        } catch (SQLException | RuntimeException e) {
            failures.add(e);
        }
        if (failures.isEmpty()) {
            return;
        }
        DriverException first = toDriverException(failures.get(0));
        for (int i = 1; i < failures.size(); i++) {
            first.addSuppressed(failures.get(i));
        }
        throw first;
    }

    // Connection.abort is missing from Android's java.sql, so it is looked up at run time; where it
    // is missing the connection is only released, and the session's setting goes with it.
    private static void abort(Connection connection) throws ReflectiveOperationException {
        Method abort;
        try {
            abort = Connection.class.getMethod("abort", Executor.class);
        } catch (NoSuchMethodException missing) {
            return;
        }
        try {
            abort.invoke(connection, (Executor) Runnable::run);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException) {
                throw new IllegalStateException("Failed to abort the database connection", cause);
            }
            throw e;
        }
    }

    private DriverException toDriverException(Exception e) {
        if (e instanceof DriverException) {
            return (DriverException) e;
        }
        if (e instanceof SQLException) {
            return failure((SQLException) e);
        }
        return new DriverException("Failed to close the database session", EnumSet.noneOf(DriverFailureKind.class), e);
    }

    private void resumeAutoCommit() throws SQLException {
        if (isAutoCommitSuspended) {
            connection.setAutoCommit(true);
            isAutoCommitSuspended = false;
        }
    }

    private void ensureOwnTransaction() {
        if (isExternal) {
            throw new IllegalStateException("session is in an external transaction it does not own");
        }
    }

    private void endTransaction() {
        isTransactionOpen = false;
        savepoints.clear();
    }

    private Savepoint requireSavepoint(String name) {
        Objects.requireNonNull(name, "name");
        Savepoint savepoint = savepoints.get(name);
        if (savepoint == null) {
            throw new IllegalArgumentException("unknown savepoint: " + name);
        }
        return savepoint;
    }

    private void dropAfter(String name, boolean isInclusive) {
        boolean isFound = false;
        Iterator<String> names = savepoints.keySet().iterator();
        while (names.hasNext()) {
            String current = names.next();
            if (isFound) {
                names.remove();
            } else if (current.equals(name)) {
                isFound = true;
                if (isInclusive) {
                    names.remove();
                }
            }
        }
    }

    private void ensureOpen() {
        if (isClosed) {
            throw new IllegalStateException("session is closed");
        }
    }

    private PreparedStatement prepare(String sql, List<Object> bindings) throws SQLException {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(bindings, "bindings");
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            int index = 1;
            for (Object value : bindings) {
                bind(statement, index++, value);
            }
            return statement;
        } catch (SQLException | RuntimeException e) {
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NULL);
        } else if (value instanceof byte[]) {
            statement.setBytes(index, (byte[]) value);
        } else if (value instanceof BigInteger) {
            statement.setBigDecimal(index, new BigDecimal((BigInteger) value));
        } else if (value instanceof Instant) {
            // JDBC 4.2 maps no SQL type to Instant; OffsetDateTime is the portable equivalent.
            statement.setObject(index, OffsetDateTime.ofInstant((Instant) value, ZoneOffset.UTC));
        } else if (!bindTyped(statement, index, value)) {
            statement.setObject(index, value);
        }
    }

    // Typed setters, because some drivers (sqlite-jdbc) store a Boolean given to setObject as text.
    private static boolean bindTyped(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof Boolean) {
            statement.setBoolean(index, (Boolean) value);
        } else if (value instanceof Byte) {
            statement.setByte(index, (Byte) value);
        } else if (value instanceof Short) {
            statement.setShort(index, (Short) value);
        } else if (value instanceof Integer) {
            statement.setInt(index, (Integer) value);
        } else if (value instanceof Long) {
            statement.setLong(index, (Long) value);
        } else if (value instanceof Float) {
            statement.setFloat(index, (Float) value);
        } else if (value instanceof Double) {
            statement.setDouble(index, (Double) value);
        } else if (value instanceof BigDecimal) {
            statement.setBigDecimal(index, (BigDecimal) value);
        } else if (value instanceof String) {
            statement.setString(index, (String) value);
        } else {
            return false;
        }
        return true;
    }

    private List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        String[] labels = new String[columnCount];
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (int i = 0; i < columnCount; i++) {
            String label = metaData.getColumnLabel(i + 1);
            if (seen.put(label, Boolean.TRUE) != null) {
                throw duplicateLabel(label);
            }
            labels[i] = label;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columnCount; i++) {
                row.put(labels[i], JdbcValues.read(resultSet.getObject(i + 1)));
            }
            rows.add(Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableList(rows);
    }

    private static DriverException duplicateLabel(String label) {
        // A label is part of the statement text, not a bound value, so naming it is safe.
        return new DriverException("Result has two columns labelled '" + label + "'; give them distinct aliases",
                EnumSet.noneOf(DriverFailureKind.class), null);
    }

    private DriverException failure(SQLException e) {
        return JdbcFailures.translate(e, productName);
    }
}

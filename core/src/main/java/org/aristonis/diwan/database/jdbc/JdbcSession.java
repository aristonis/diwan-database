package org.aristonis.diwan.database.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.driver.DatabaseFamily;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.exception.DiwanError;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.sqlite.SQLiteScripts;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.driver.DriverFailureMessages;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.driver.LockWaitGuard;
import org.aristonis.diwan.database.driver.RowCursor;

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

    // Rows a cursor asks the database for per round trip: enough to keep the round trips few, few
    // enough that a wide row cannot fill memory.
    private static final int CURSOR_FETCH_SIZE = 500;

    private final JdbcConnectionProvider provider;
    private final Connection connection;
    private final boolean isExternal;
    /** The savepoints of the open transaction. */
    private final JdbcSavepoints savepoints = new JdbcSavepoints();
    /** The product name used to read vendor codes, or {@code null} if unknown. */
    private final String productName;
    /** The lock wait strategy of the database, or {@code null} when it has none. */
    private final JdbcLockWait lockWait;

    /** Whether auto-commit was off at open and was switched on for the session's life. */
    private final boolean shouldRestoreManualCommit;
    /** Whether the database streams a cursor only inside a transaction. */
    private final boolean streamsInTransaction;

    private boolean isTransactionOpen;
    /** Whether {@link #begin()} switched auto-commit off for the current transaction. */
    private boolean isAutoCommitSuspended;
    private boolean isClosed;

    private JdbcSession(JdbcConnectionProvider provider, Connection connection, boolean isExternal,
            String productName, boolean shouldRestoreManualCommit, boolean streamsInTransaction) {
        this.provider = provider;
        this.streamsInTransaction = streamsInTransaction;
        this.connection = connection;
        this.isExternal = isExternal;
        this.productName = productName;
        this.lockWait = JdbcLockWait.forFamily(DatabaseFamily.of(productName));
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
            DriverInfo described = describe(connection, info);
            String productName = described.databaseName();
            boolean shouldRestoreManualCommit = false;
            if (!isExternal && !connection.getAutoCommit()) {
                connection.setAutoCommit(true);
                shouldRestoreManualCommit = true;
            }
            return new JdbcSession(provider, connection, isExternal, productName, shouldRestoreManualCommit,
                    described.supports(DriverCapability.STREAMS_IN_TRANSACTION));
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

    private static DriverInfo describe(Connection connection, AtomicReference<DriverInfo> info)
            throws SQLException {
        DriverInfo cached = info.get();
        if (cached == null) {
            info.compareAndSet(null, JdbcDriver.describe(connection.getMetaData()));
            cached = info.get();
        }
        return cached;
    }

    @Override
    public List<Map<String, Object>> query(String sql, List<Object> bindings) {
        ensureOpen();
        try (PreparedStatement statement = JdbcStatements.prepare(connection, productName, sql, bindings);
                ResultSet resultSet = statement.executeQuery()) {
            return JdbcStatements.readRows(resultSet, productName);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote The statement is forward-only and read-only with a fetch size of 500. On a database
     *     that reports {@link DriverCapability#STREAMS_IN_TRANSACTION}, a cursor opened outside any
     *     transaction switches auto-commit off for its life and commits when it closes (rolls back
     *     after a failure), then switches auto-commit back on.
     */
    @Override
    public RowCursor cursor(String sql, List<Object> bindings) {
        ensureOpen();
        boolean ownsTransaction = streamsInTransaction && !isExternal && !isTransactionOpen;
        if (ownsTransaction) {
            begin();
        }
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = JdbcStatements.prepare(connection, productName, sql, bindings, true);
            statement.setFetchSize(CURSOR_FETCH_SIZE);
            resultSet = statement.executeQuery();
            JdbcColumns columns = JdbcColumns.of(resultSet.getMetaData(), productName);
            return new JdbcRowCursor(this, statement, resultSet, columns, ownsTransaction);
        } catch (SQLException | RuntimeException e) {
            throw cursorOpenFailure(e, statement, resultSet, ownsTransaction);
        }
    }

    // Everything the cursor opened is released before the failure is reported; each step runs even
    // when an earlier one threw an Error, which is then reported in place of the open failure.
    private RuntimeException cursorOpenFailure(Exception e, PreparedStatement statement, ResultSet resultSet,
            boolean ownsTransaction) {
        List<Throwable> failures = new ArrayList<>();
        try {
            if (resultSet != null) {
                resultSet.close();
            }
        } catch (Throwable closeFailure) {
            failures.add(closeFailure);
        }
        try {
            if (statement != null) {
                statement.close();
            }
        } catch (Throwable closeFailure) {
            failures.add(closeFailure);
        }
        if (ownsTransaction) {
            List<Exception> transactionFailures = new ArrayList<>();
            try {
                endCursorTransaction(false, transactionFailures);
            } catch (Throwable endFailure) {
                failures.add(endFailure);
            }
            failures.addAll(transactionFailures);
        }
        RuntimeException error = e instanceof SQLException ? failure((SQLException) e) : (RuntimeException) e;
        Error fatal = null;
        for (Throwable secondary : failures) {
            if (fatal == null && secondary instanceof Error) {
                fatal = (Error) secondary;
            } else {
                error.addSuppressed(secondary);
            }
        }
        if (fatal != null) {
            fatal.addSuppressed(error);
            throw fatal;
        }
        return error;
    }

    /**
     * Throws the first of a cursor's release failures with the rest suppressed: an Error as it is,
     * any other failure as a {@link DriverException}. Returns when there are none.
     */
    void throwFirst(List<Throwable> failures) {
        if (failures.isEmpty()) {
            return;
        }
        Throwable first = failures.get(0);
        Throwable thrown = first instanceof Error ? first : toDriverException((Exception) first);
        for (int i = 1; i < failures.size(); i++) {
            thrown.addSuppressed(failures.get(i));
        }
        if (thrown instanceof Error) {
            throw (Error) thrown;
        }
        throw (RuntimeException) thrown;
    }

    /**
     * Ends the transaction a cursor opened: commits after a clean read, rolls back otherwise or when
     * the commit fails. Both turn auto-commit back on. Failures are added to {@code failures}.
     */
    void endCursorTransaction(boolean isClean, List<Exception> failures) {
        if (isClean) {
            try {
                commit();
                return;
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        try {
            rollback();
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    /** Translates a JDBC failure with this session's vendor codes. */
    DriverException translate(SQLException e) {
        return failure(e);
    }

    @Override
    public int update(String sql, List<Object> bindings) {
        ensureOpen();
        try (PreparedStatement statement = JdbcStatements.prepare(connection, productName, sql, bindings)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public boolean execute(String sql, List<Object> bindings) {
        ensureOpen();
        try (PreparedStatement statement = JdbcStatements.prepare(connection, productName, sql, bindings)) {
            return statement.execute();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public void executeUnprepared(String sql) {
        ensureOpen();
        Objects.requireNonNull(sql, "sql");
        // The SQLite driver runs only a string's first statement; a script runs whole, as on PDO.
        List<String> statements = DatabaseFamily.of(productName) == DatabaseFamily.SQLITE
                ? SQLiteScripts.split(sql) : Collections.singletonList(sql);
        try (Statement statement = connection.createStatement()) {
            for (String one : statements) {
                statement.execute(one);
            }
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
            if (DatabaseFamily.of(productName) == DatabaseFamily.SQLITE && isAutoCommitSuspended) {
                // sqlite-jdbc commit() runs "commit" and then begins again with the configured mode, so
                // a commit that landed could report busy. Switching auto-commit on is a commit too, and
                // runs only "commit".
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException e) {
                    DriverException commitFailure = commitFailure(failure(e));
                    restoreTransactionState(commitFailure);
                    throw commitFailure;
                }
                isAutoCommitSuspended = false;
                endTransaction();
            } else {
                connection.commit();
                endTransaction();
                resumeAutoCommit();
            }
        } catch (SQLException e) {
            throw commitFailure(failure(e));
        }
    }

    @Override
    public void rollback() {
        ensureOpen();
        ensureOwnTransaction();
        try {
            // On SQLite the driver begins again after a rollback, so this can wait for another writer;
            // nothing is applied twice.
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
        savepoints.requireNewName(name);
        try {
            savepoints.put(name, connection.setSavepoint(name));
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public void rollbackToSavepoint(String name) {
        ensureOpen();
        Savepoint savepoint = savepoints.require(name);
        try {
            connection.rollback(savepoint);
        } catch (SQLException e) {
            throw failure(e);
        }
        // The rolled-back-to savepoint survives; every later one is gone.
        savepoints.dropAfter(name, false);
    }

    @Override
    public void releaseSavepoint(String name) {
        ensureOpen();
        Savepoint savepoint = savepoints.require(name);
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            throw failure(e);
        }
        savepoints.dropAfter(name, true);
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

    DriverException toDriverException(Exception e) {
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

    private void ensureOpen() {
        if (isClosed) {
            throw new IllegalStateException("session is closed");
        }
    }

    // A commit that lost its connection may have landed; the kind says so, apart from a plain loss.
    /**
     * Puts the driver back into manual-commit mode after a failed switch to auto-commit.
     *
     * <p>sqlite-jdbc flips its auto-commit flag before it runs COMMIT. When that COMMIT fails, SQLite keeps
     * the transaction open but the driver reports auto-commit, and a rollback would then be refused. Setting
     * the flag back to false either fails harmlessly, because SQLite still holds the transaction and the
     * flag is already false, or, if SQLite ended the transaction, opens an empty one. Either way the
     * session stays in a transaction and the caller's rollback works.
     *
     * @param commitFailure the commit failure that receives any restore failure as suppressed
     */
    private void restoreTransactionState(DriverException commitFailure) {
        try {
            connection.setAutoCommit(false);
        } catch (SQLException restore) {
            commitFailure.addSuppressed(restore);
        }
    }

    private DriverException commitFailure(DriverException failure) {
        if (!failure.kinds().contains(DriverFailureKind.CONNECTION_LOST)) {
            return failure;
        }
        Set<DriverFailureKind> kinds = EnumSet.copyOf(failure.kinds());
        kinds.add(DriverFailureKind.COMMIT_OUTCOME_UNKNOWN);
        String codeName = DatabaseFamily.of(productName) == DatabaseFamily.SQLITE
                ? DriverFailureMessages.sqliteCodeName(failure.vendorCode()).orElse(null) : null;
        DiwanError error = DiwanError.of(kinds, failure.error().key().orElse(null),
                failure.error().table().orElse(null), failure.error().columns());
        String message = DriverFailureMessages.message(kinds, failure.sqlState().orElse(null),
                failure.vendorCode(), codeName, error);
        return new DriverException(message, failure.sqlState().orElse(null), failure.vendorCode(),
                failure.databaseMessage().orElse(null), failure.getCause(), kinds,
                failure.error().key().orElse(null), failure.error().table().orElse(null),
                failure.error().columns());
    }

    private DriverException failure(SQLException e) {
        return JdbcFailures.translate(e, productName);
    }

    @Override
    public LockWaitGuard lockWait(long millis) {
        if (lockWait == null) {
            return DriverSession.super.lockWait(millis);
        }
        return new TranslatingGuard(lockWait.guard(connection, millis));
    }

    @Override
    public LockWaitGuard statementLockWait(long millis) {
        if (lockWait == null) {
            return DriverSession.super.statementLockWait(millis);
        }
        return new TranslatingGuard(lockWait.statement(connection, millis));
    }

    @Override
    public OptionalInt exactDecimalDigits() {
        return lockWait == null ? DriverSession.super.exactDecimalDigits() : lockWait.exactDecimalDigits();
    }

    /** Translates the SQL failure of a guard step like any other failure of the session. */
    private final class TranslatingGuard implements LockWaitGuard {
        private final LockWaitGuard delegate;

        TranslatingGuard(LockWaitGuard delegate) {
            this.delegate = delegate;
        }

        @Override
        public void beforeBegin() {
            try {
                delegate.beforeBegin();
            } catch (JdbcLockWait.Failure e) {
                throw failure(e.sqlException());
            }
        }

        @Override
        public void afterBegin() {
            try {
                delegate.afterBegin();
            } catch (JdbcLockWait.Failure e) {
                throw failure(e.sqlException());
            }
        }

        @Override
        public void restore() {
            try {
                delegate.restore();
            } catch (JdbcLockWait.Failure e) {
                throw failure(e.sqlException());
            }
        }
    }
}

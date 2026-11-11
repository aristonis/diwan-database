package org.aristonis.diwan.database.android;

import android.database.Cursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteProgram;
import android.database.sqlite.SQLiteStatement;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.exception.DiwanError;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.driver.DriverFailureMessages;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.driver.LockWaitGuard;
import org.aristonis.diwan.database.driver.RowCursor;
import org.aristonis.diwan.database.driver.sqlite.SQLiteScripts;
import org.aristonis.diwan.database.driver.sqlite.SQLiteText;

/**
 * One session over the shared {@link SQLiteDatabase}.
 *
 * <p>Android ties a transaction to the thread that began it: statements from another thread run
 * outside it, and only that thread can end it. So a transaction, and every statement inside it,
 * stays on the thread that began it; ending it from another thread fails and leaves it for its own
 * thread to end. Closing or discarding the session ends a transaction it began without committing
 * it, and leaves the database open.
 *
 * <p>A {@linkplain #cursor cursor} opens no transaction and keeps its {@link SQLiteCursor} open.
 * When the loop passes the end of the cursor's window, Android refills it by running the query
 * again, so rows changed during the loop can be skipped or read twice; use {@code lazyById} or
 * {@code chunkById} for big or changing tables. A cursor is used on one thread only.
 */
final class AndroidSqliteSession implements DriverSession {

    private static final int SQLITE_EXACT_DIGITS = 15;

    private static final int SQLITE_CONSTRAINT_FOREIGNKEY = 787;

    // Columns of PRAGMA foreign_key_check: table, rowid, parent, fkid.
    private static final int FK_CHECK_TABLE = 0;
    private static final int FK_CHECK_PARENT = 2;

    private static final Pattern SAVEPOINT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,29}");

    private final SQLiteDatabase database;
    private final List<String> savepoints = new ArrayList<>();
    private final boolean isNonExclusive;
    private boolean hasBegun;
    private Thread owner;
    private boolean isClosed;

    AndroidSqliteSession(SQLiteDatabase database) {
        this(database, false);
    }

    AndroidSqliteSession(SQLiteDatabase database, boolean isNonExclusive) {
        this.database = database;
        this.isNonExclusive = isNonExclusive;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Sets {@code PRAGMA busy_timeout} before the transaction begins and puts the earlier
     *     value back after it ends, so the wait covers the lock taken when the transaction starts.
     * @return a guard that sets and restores the busy timeout of the shared database
     */
    @Override
    public LockWaitGuard lockWait(long millis) {
        ensureOpen();
        return new BusyTimeoutGuard(millis);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always, since SQLite has no per-statement lock wait
     */
    @Override
    public LockWaitGuard statementLockWait(long millis) {
        throw new UnsupportedOperationException(
                "A lock wait timeout on a single statement is not supported by SQLite; use TransactionOptions.lockWait");
    }

    /**
     * {@inheritDoc}
     *
     * @return 15, the digits a SQLite floating point number keeps exactly
     */
    @Override
    public OptionalInt exactDecimalDigits() {
        return OptionalInt.of(SQLITE_EXACT_DIGITS);
    }

    /** Reads {@code PRAGMA busy_timeout} of a database, in milliseconds. */
    static long readBusyTimeout(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery("PRAGMA busy_timeout", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    /** Sets {@code PRAGMA busy_timeout} of a database; the pragma returns a row, so it runs as a query. */
    static void writeBusyTimeout(SQLiteDatabase database, long millis) {
        try (Cursor cursor = database.rawQuery("PRAGMA busy_timeout = " + millis, null)) {
            cursor.moveToFirst();
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    /**
     * Sets the busy timeout for one transaction and puts the earlier value back.
     *
     * <p>{@code busy_timeout} belongs to the {@link SQLiteDatabase}, not to one session, so
     * lock-wait transactions that overlap on the same database share it: the one that ends last
     * restores the value it saw when it started, which can be the other transaction's shorter wait
     * instead of the database's own.
     */
    private final class BusyTimeoutGuard implements LockWaitGuard {

        private final long millis;
        private long saved;
        private boolean hasSaved;

        BusyTimeoutGuard(long millis) {
            this.millis = millis;
        }

        @Override
        public void beforeBegin() {
            saved = readBusyTimeout(database);
            hasSaved = true;
            writeBusyTimeout(database, millis);
        }

        @Override
        public void afterBegin() {
            // The wait is already applied before the transaction starts.
        }

        @Override
        public void restore() {
            if (hasSaved) {
                hasSaved = false;
                writeBusyTimeout(database, saved);
            }
        }
    }

    @Override
    public List<Map<String, Object>> query(String sql, List<Object> bindings) {
        ensureOpen();
        Objects.requireNonNull(sql, "sql");
        List<Object> values = convert(bindings);
        try (Cursor cursor = database.rawQueryWithFactory((db, driver, editTable, query) -> {
            bindAll(query, values);
            return new SQLiteCursor(driver, editTable, query);
        }, sql, null, null)) {
            String[] labels = cursor.getColumnNames();
            refuseRepeated(labels);
            List<Map<String, Object>> rows = new ArrayList<>();
            while (cursor.moveToNext()) {
                rows.add(readRow(cursor, labels));
            }
            return rows;
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Opens no transaction: Android ties a transaction to the thread, so one opened for
     *     the cursor would take in every write the loop makes and roll it back when the cursor
     *     closes. Inside a transaction the cursor reads in it and leaves it open.
     */
    @Override
    public RowCursor cursor(String sql, List<Object> bindings) {
        ensureOpen();
        Objects.requireNonNull(sql, "sql");
        List<Object> values = convert(bindings);
        Cursor cursor;
        try {
            cursor = database.rawQueryWithFactory((db, driver, editTable, query) -> {
                bindAll(query, values);
                return new SQLiteCursor(driver, editTable, query);
            }, sql, null, null);
        } catch (RuntimeException e) {
            throw translate(e);
        }
        try {
            String[] labels = cursor.getColumnNames();
            refuseRepeated(labels);
            return new AndroidRowCursor(cursor, labels, Thread.currentThread());
        } catch (RuntimeException e) {
            try {
                cursor.close();
            } catch (RuntimeException closing) {
                e.addSuppressed(closing);
            }
            throw translate(e);
        }
    }

    static Map<String, Object> readRow(Cursor cursor, String[] labels) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < labels.length; i++) {
            row.put(labels[i], read(cursor, i));
        }
        return row;
    }

    @Override
    public int update(String sql, List<Object> bindings) {
        ensureOpen();
        try (SQLiteStatement statement = prepare(sql, bindings)) {
            return statement.executeUpdateDelete();
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public boolean execute(String sql, List<Object> bindings) {
        ensureOpen();
        try (SQLiteStatement statement = prepare(sql, bindings)) {
            statement.execute();
            return false;
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public void executeUnprepared(String sql) {
        ensureOpen();
        // execSQL runs only the first statement of a string; a script runs whole.
        try {
            for (String statement : SQLiteScripts.split(Objects.requireNonNull(sql, "sql"))) {
                database.execSQL(statement);
            }
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public boolean inExternalTransaction() {
        ensureOpen();
        return !hasBegun && database.inTransaction();
    }

    @Override
    public void begin() {
        ensureOpen();
        if (hasBegun) {
            throw new IllegalStateException("A transaction is already open");
        }
        if (database.inTransaction()) {
            throw new IllegalStateException("The thread is already inside a transaction this session did not begin");
        }
        try {
            if (isNonExclusive) {
                database.beginTransactionNonExclusive();
            } else {
                database.beginTransaction();
            }
        } catch (RuntimeException e) {
            throw translate(e);
        }
        hasBegun = true;
        owner = Thread.currentThread();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Android reports a deferred foreign key violation only after the commit has gone
     *     through, when it can no longer be rolled back. So before the commit this reads
     *     {@code PRAGMA defer_foreign_keys}; when it is on, {@code PRAGMA foreign_key_check} (the
     *     statement form, since the table-valued function needs a newer SQLite than API 28) runs,
     *     and a violation ends the transaction without marking it successful, so Android rolls it
     *     back, and fails with a foreign key {@link DriverException} naming the child table. The
     *     check runs only when {@code defer_foreign_keys} is on, so a key declared
     *     {@code DEFERRABLE INITIALLY DEFERRED} in raw DDL is not checked. {@code foreign_key_check}
     *     scans every foreign key of the database, not only the tables this transaction wrote, so
     *     an orphan row left from before the transaction also fails it; with {@code foreign_keys}
     *     off nothing is checked. If
     *     the pragma itself fails, that failure is thrown and the transaction stays open for the
     *     caller's rollback.
     */
    @Override
    public void commit() {
        ensureOpen();
        requireBegun();
        requireOwner();
        DriverException violation = deferredViolation();
        if (violation != null) {
            try {
                end();
            } catch (RuntimeException e) {
                violation.addSuppressed(e);
            }
            throw violation;
        }
        try {
            database.setTransactionSuccessful();
            database.endTransaction();
        } catch (RuntimeException e) {
            throw translate(e);
        }
        ended();
    }

    // Returns null when deferral is off or the check finds no orphan row.
    private DriverException deferredViolation() {
        try {
            if (!isDeferralOn()) {
                return null;
            }
            try (Cursor cursor = database.rawQuery("PRAGMA foreign_key_check", null)) {
                if (!cursor.moveToFirst()) {
                    return null;
                }
                return foreignKeyFailure(cursor.getString(FK_CHECK_TABLE), cursor.getString(FK_CHECK_PARENT));
            }
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    // With foreign keys off SQLite enforces nothing at commit, so nothing is checked either.
    private boolean isDeferralOn() {
        return isPragmaOn("PRAGMA foreign_keys") && isPragmaOn("PRAGMA defer_foreign_keys");
    }

    private boolean isPragmaOn(String pragma) {
        try (Cursor cursor = database.rawQuery(pragma, null)) {
            return cursor.moveToFirst() && cursor.getLong(0) == 1L;
        }
    }

    private static DriverException foreignKeyFailure(String table, String parent) {
        Set<DriverFailureKind> kinds = EnumSet.of(DriverFailureKind.FOREIGN_KEY_VIOLATION);
        DiwanError error = DiwanError.of(kinds, null, table, Collections.<String>emptyList());
        String codeName = DriverFailureMessages.sqliteCodeName(SQLITE_CONSTRAINT_FOREIGNKEY).orElse(null);
        return new DriverException(
                DriverFailureMessages.message(kinds, null, SQLITE_CONSTRAINT_FOREIGNKEY, codeName, error), null,
                SQLITE_CONSTRAINT_FOREIGNKEY, "FOREIGN KEY constraint failed (table " + table + ", parent " + parent
                        + ")", null, kinds, null, table, Collections.<String>emptyList());
    }

    @Override
    public void rollback() {
        ensureOpen();
        requireBegun();
        requireOwner();
        end();
    }

    @Override
    public void savepoint(String name) {
        ensureOpen();
        requireBegun();
        checkName(name);
        String key = name.toLowerCase(Locale.ROOT);
        if (savepoints.contains(key)) {
            throw new IllegalArgumentException("savepoint already exists: " + name);
        }
        executeUnprepared("savepoint " + name);
        savepoints.add(key);
    }

    @Override
    public void rollbackToSavepoint(String name) {
        ensureOpen();
        int at = requireSavepoint(name);
        executeUnprepared("rollback to savepoint " + name);
        // The rolled-back-to savepoint survives; every later one is gone.
        savepoints.subList(at + 1, savepoints.size()).clear();
    }

    @Override
    public void releaseSavepoint(String name) {
        ensureOpen();
        int at = requireSavepoint(name);
        executeUnprepared("release savepoint " + name);
        savepoints.subList(at, savepoints.size()).clear();
    }

    @Override
    public void close() {
        if (isClosed) {
            return;
        }
        if (hasBegun) {
            requireOwner();
            end();
        }
        isClosed = true;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote The database is shared and stays open; discarding ends an open transaction this
     *     session began without committing it, which is all a later user could inherit.
     */
    @Override
    public void discard() {
        close();
    }

    // State is cleared only once Android has ended the transaction, so a failure leaves it to retry.
    private void end() {
        try {
            database.endTransaction();
        } catch (RuntimeException e) {
            throw translate(e);
        }
        ended();
    }

    private void ended() {
        hasBegun = false;
        owner = null;
        savepoints.clear();
    }

    private void requireOwner() {
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException("The transaction belongs to thread " + owner.getName()
                    + "; Android lets only that thread end it");
        }
    }

    private SQLiteStatement prepare(String sql, List<Object> bindings) {
        SQLiteStatement statement = database.compileStatement(Objects.requireNonNull(sql, "sql"));
        try {
            bindAll(statement, convert(bindings));
            return statement;
        } catch (RuntimeException e) {
            statement.close();
            throw e;
        }
    }

    private static void bindAll(SQLiteProgram program, List<Object> values) {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            int index = i + 1;
            if (value == null) {
                program.bindNull(index);
            } else if (value instanceof Long) {
                program.bindLong(index, (Long) value);
            } else if (value instanceof Double) {
                program.bindDouble(index, (Double) value);
            } else if (value instanceof byte[]) {
                program.bindBlob(index, (byte[]) value);
            } else {
                program.bindString(index, (String) value);
            }
        }
    }

    // SQLite has four value types; dates and decimals arrive here as SQLiteText already wrote them.
    static List<Object> convert(List<Object> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        List<Object> values = new ArrayList<>(bindings.size());
        for (Object value : bindings) {
            SQLiteText.refuseNonFinite(value, values.size() + 1);
            values.add(convert(SQLiteText.toStorage(value)));
        }
        return values;
    }

    private static Object convert(Object value) {
        if (value == null || value instanceof String || value instanceof byte[]) {
            return value;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1L : 0L;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger) {
            BigInteger number = (BigInteger) value;
            // Past a long, stored as text, as the JDBC driver stores it through BigDecimal.
            return number.bitLength() < Long.SIZE ? (Object) number.longValue() : number.toString();
        }
        if (value instanceof Double || value instanceof Float) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof Instant) {
            return OffsetDateTime.ofInstant((Instant) value, ZoneOffset.UTC).toString();
        }
        throw new IllegalArgumentException("Unsupported binding type " + value.getClass().getName());
    }

    private static Object read(Cursor cursor, int column) {
        switch (cursor.getType(column)) {
            case Cursor.FIELD_TYPE_NULL:
                return null;
            case Cursor.FIELD_TYPE_INTEGER:
                return cursor.getLong(column);
            case Cursor.FIELD_TYPE_FLOAT:
                return cursor.getDouble(column);
            case Cursor.FIELD_TYPE_BLOB:
                return cursor.getBlob(column);
            default:
                return cursor.getString(column);
        }
    }

    private static void refuseRepeated(String[] labels) {
        List<String> seen = new ArrayList<>();
        for (String label : labels) {
            if (seen.contains(label)) {
                throw new DriverException("The result has the column label " + label + " twice",
                        java.util.Collections.emptySet(), null);
            }
            seen.add(label);
        }
    }

    static DriverException translate(RuntimeException failure) {
        if (failure instanceof DriverException) {
            return (DriverException) failure;
        }
        if (AndroidSqliteFailures.isSqliteFailure(failure)) {
            return AndroidSqliteFailures.translate(failure);
        }
        throw failure;
    }

    private static void checkName(String name) {
        if (name == null || !SAVEPOINT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "savepoint name must be 1 to 30 ASCII letters, digits or underscores, not starting with a digit");
        }
    }

    private int requireSavepoint(String name) {
        checkName(name);
        // SQLite compares savepoint names without case.
        int at = savepoints.indexOf(name.toLowerCase(Locale.ROOT));
        if (at < 0) {
            throw new IllegalArgumentException("no such savepoint: " + name);
        }
        return at;
    }

    private void requireBegun() {
        if (!hasBegun) {
            throw new IllegalStateException("No transaction is open");
        }
    }

    private void ensureOpen() {
        if (isClosed) {
            throw new IllegalStateException("The session is closed");
        }
    }
}

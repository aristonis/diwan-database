package org.aristonis.diwan.database.android;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.EnumSet;
import java.util.Objects;
import java.util.OptionalInt;
import org.aristonis.diwan.database.driver.Driver;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.driver.DriverSession;

/**
 * A driver over Android's {@link SQLiteDatabase}, for Android 9 (API 28) and later.
 *
 * <p>Every session shares the one database; Android serializes access to it, and a session never
 * closes it, since the application owns its lifecycle. Pass the driver to
 * {@code Connection.builder().driver(...)} and use the SQLite schema grammar for migrations.
 * Turn foreign keys on with {@link SQLiteDatabase#setForeignKeyConstraintsEnabled(boolean)}, for
 * example in {@code SQLiteOpenHelper.onConfigure}.
 */
public final class AndroidSqliteDriver implements Driver {

    private final SQLiteOpenHelper helper;
    private static final int SQLITE_EXACT_DIGITS = 15;
    private static final long NO_BUSY_TIMEOUT = 0L;

    private final boolean isNonExclusive;
    private final long busyTimeoutMillis;
    private SQLiteDatabase database;
    private volatile DriverInfo info;

    private AndroidSqliteDriver(SQLiteDatabase database, SQLiteOpenHelper helper, boolean isNonExclusive,
            long busyTimeoutMillis) {
        this.database = database;
        this.helper = helper;
        this.isNonExclusive = isNonExclusive;
        this.busyTimeoutMillis = busyTimeoutMillis;
    }

    /**
     * Creates a driver over an open database.
     *
     * @param database the database, never {@code null}; it stays open after the driver is done
     * @return the driver, never {@code null}
     * @throws NullPointerException if {@code database} is {@code null}
     */
    public static AndroidSqliteDriver of(SQLiteDatabase database) {
        return new AndroidSqliteDriver(Objects.requireNonNull(database, "database"), null, false, NO_BUSY_TIMEOUT);
    }

    /**
     * Creates a driver over a helper's writable database.
     *
     * <p>The database is opened on first use, not here, since opening runs the helper's
     * {@code onCreate} or {@code onUpgrade}; use the driver off the main thread.
     *
     * @param helper the helper, never {@code null}
     * @return the driver, never {@code null}
     * @throws NullPointerException if {@code helper} is {@code null}
     */
    public static AndroidSqliteDriver of(SQLiteOpenHelper helper) {
        return new AndroidSqliteDriver(null, Objects.requireNonNull(helper, "helper"), false, NO_BUSY_TIMEOUT);
    }

    // Used by the connector: sessions begin with beginTransactionNonExclusive() when the flag is set.
    // A positive busyTimeoutMillis is set as PRAGMA busy_timeout once the database opens.
    static AndroidSqliteDriver of(SQLiteOpenHelper helper, boolean isNonExclusive, long busyTimeoutMillis) {
        return new AndroidSqliteDriver(null, Objects.requireNonNull(helper, "helper"), isNonExclusive,
                busyTimeoutMillis);
    }

    private synchronized SQLiteDatabase database() {
        if (database == null) {
            SQLiteDatabase opened = helper.getWritableDatabase();
            if (busyTimeoutMillis > NO_BUSY_TIMEOUT) {
                AndroidSqliteSession.writeBusyTimeout(opened, busyTimeoutMillis);
            }
            database = opened;
        }
        return database;
    }

    /**
     * {@inheritDoc}
     *
     * @return a session over the shared database
     * @throws android.database.sqlite.SQLiteException if a helper's database cannot be opened
     */
    @Override
    public DriverSession open() {
        return new AndroidSqliteSession(database(), isNonExclusive);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code SQLite}, the version from {@code sqlite_version()}, with savepoint release and
     *     transactional DDL
     */
    @Override
    public DriverInfo info() {
        DriverInfo known = info;
        if (known == null) {
            String version;
            try (AndroidSqliteSession session = new AndroidSqliteSession(database())) {
                version = String.valueOf(session.query("select sqlite_version() as v",
                        java.util.Collections.emptyList()).get(0).get("v"));
            }
            known = new DriverInfo("SQLite", version,
                    EnumSet.of(DriverCapability.SAVEPOINT_RELEASE, DriverCapability.TRANSACTIONAL_DDL));
            info = known;
        }
        return known;
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
}

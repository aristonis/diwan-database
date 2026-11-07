package org.aristonis.diwan.database.android;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.EnumSet;
import java.util.Objects;
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
    private final boolean isNonExclusive;
    private SQLiteDatabase database;
    private volatile DriverInfo info;

    private AndroidSqliteDriver(SQLiteDatabase database, SQLiteOpenHelper helper, boolean isNonExclusive) {
        this.database = database;
        this.helper = helper;
        this.isNonExclusive = isNonExclusive;
    }

    /**
     * Creates a driver over an open database.
     *
     * @param database the database, never {@code null}; it stays open after the driver is done
     * @return the driver, never {@code null}
     * @throws NullPointerException if {@code database} is {@code null}
     */
    public static AndroidSqliteDriver of(SQLiteDatabase database) {
        return new AndroidSqliteDriver(Objects.requireNonNull(database, "database"), null, false);
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
        return new AndroidSqliteDriver(null, Objects.requireNonNull(helper, "helper"), false);
    }

    // Used by the connector: sessions begin with beginTransactionNonExclusive() when the flag is set.
    static AndroidSqliteDriver of(SQLiteOpenHelper helper, boolean isNonExclusive) {
        return new AndroidSqliteDriver(null, Objects.requireNonNull(helper, "helper"), isNonExclusive);
    }

    private synchronized SQLiteDatabase database() {
        if (database == null) {
            database = helper.getWritableDatabase();
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
}

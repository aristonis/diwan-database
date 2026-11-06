package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteException;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidSqliteFailuresTest {

    @Test
    public void aUniqueFailureHasItsKindAndNoValues() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        Connection db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table u (email text unique)");
        db.insert("insert into u (email) values (?)", "secret@example.com");

        UniqueConstraintViolationException error = assertThrows(UniqueConstraintViolationException.class,
                () -> db.insert("insert into u (email) values (?)", "secret@example.com"));

        assertFalse(error.getMessage().contains("secret@example.com"));
        database.close();
    }

    @Test
    public void aForeignKeyFailureIsItsOwnException() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        database.setForeignKeyConstraintsEnabled(true);
        Connection db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table p (id integer primary key)");
        db.statement("create table k (p_id integer references p (id))");

        assertThrows(org.aristonis.diwan.database.exception.ForeignKeyViolationException.class,
                () -> db.insert("insert into k values (?)", 9));
        database.close();
    }

    @Test
    public void otherConstraintFailuresHaveNoKind() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        Connection db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table n (x text not null)");

        QueryException error = assertThrows(QueryException.class, () -> db.insert("insert into n (x) values (null)"));

        assertFalse(error instanceof UniqueConstraintViolationException);
        database.close();
    }

    @Test
    public void codesAndKindsAreReadFromAndroidMessages() {
        DriverException unique = AndroidSqliteFailures.translate(new SQLiteConstraintException(
                "UNIQUE constraint failed: u.email (code 2067 SQLITE_CONSTRAINT_UNIQUE)"));
        DriverException primary = AndroidSqliteFailures.translate(new SQLiteConstraintException(
                "PRIMARY KEY must be unique (code 19)"));
        DriverException locked = AndroidSqliteFailures.translate(new SQLiteDatabaseLockedException(
                "database is locked (code 5 SQLITE_BUSY)"));
        DriverException busyText = AndroidSqliteFailures.translate(new SQLiteException("busy (code 261)"));
        DriverException plain = AndroidSqliteFailures.translate(new SQLiteException("no such table: x"));
        DriverException silent = AndroidSqliteFailures.translate(new SQLiteException());

        assertTrue(unique.kinds().contains(DriverFailureKind.UNIQUE_VIOLATION));
        assertEquals(2067, unique.vendorCode());
        assertTrue(primary.kinds().contains(DriverFailureKind.UNIQUE_VIOLATION));
        assertTrue(locked.kinds().contains(DriverFailureKind.CONCURRENCY));
        assertTrue(busyText.kinds().contains(DriverFailureKind.CONCURRENCY));
        assertTrue(plain.kinds().isEmpty());
        assertEquals(0, plain.vendorCode());
        assertTrue(silent.kinds().isEmpty());
        assertFalse(unique.getMessage().contains("email"));
    }
}

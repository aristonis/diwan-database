package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import java.util.Arrays;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.DiwanError;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.exception.QueryException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidDiwanErrorTest {

    private static DiwanError errorOf(QueryException failure) {
        return ((DriverException) failure.getCause()).error();
    }

    @Test
    public void uniqueGivesTheColumns() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        Connection db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table u (email text unique)");
        db.insert("insert into u (email) values (?)", "secret@example.com");

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into u (email) values (?)", "secret@example.com"));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0001", error.code());
        assertFalse(error.key().isPresent());
        assertEquals(Arrays.asList("email"), error.columns());
        assertFalse(failure.getMessage().contains("secret@example.com"));
        database.close();
    }

    @Test
    public void notNullGivesTheColumn() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        Connection db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table n (x text not null)");

        QueryException failure = assertThrows(QueryException.class,
                () -> db.insert("insert into n (x) values (null)"));

        DiwanError error = errorOf(failure);
        assertEquals("DWN-CST-0004", error.code());
        assertEquals(Arrays.asList("x"), error.columns());
        database.close();
    }

    @Test
    public void checkAndForeignKeyAreReadFromAndroidMessages() {
        DriverException check = AndroidSqliteFailures.translate(
                new SQLiteConstraintException("CHECK constraint failed: positive (code 275 SQLITE_CONSTRAINT_CHECK)"));
        DriverException foreign = AndroidSqliteFailures.translate(
                new SQLiteConstraintException("FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)"));
        DriverException notNull = AndroidSqliteFailures.translate(
                new SQLiteConstraintException("NOT NULL constraint failed: t.a (code 1299)"));

        assertEquals("DWN-CST-0003", check.error().code());
        assertEquals("positive", check.error().key().orElseThrow());
        assertTrue(check.getMessage().endsWith(" [DWN-CST-0003 check violation: positive]"));
        assertEquals("DWN-CST-0002", foreign.error().code());
        assertFalse(foreign.error().key().isPresent());
        assertTrue(foreign.error().columns().isEmpty());
        assertEquals("DWN-CST-0004", notNull.error().code());
        assertEquals(Arrays.asList("a"), notNull.error().columns());
    }
}

package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteVersion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Upsert needs SQLite 3.24 (Android API 30+); insertOrIgnore works on every version. The test
 * follows whatever SQLite Robolectric reports for API 28, which is 3.22 on a real Android 9.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidUpsertTest {

    private SQLiteDatabase database;
    private Connection db;
    private QueryFactory query;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table users (id integer primary key autoincrement, email text unique, name text)");
        query = SQLiteQuery.of(db);
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    @Test
    public void upsertIsRefusedBeforeAnySqlOnOldSqliteAndWorksOtherwise() {
        String version = db.getDriverInfo().databaseVersion();
        List<Map<String, Object>> rows = List.of(Map.of("email", "a@x", "name", "A"));

        if (SQLiteVersion.atLeast(version, 3, 24, 0)) {
            query.table("users").upsert(rows, List.of("email"));
            query.table("users").upsert(List.of(Map.of("email", "a@x", "name", "A2")), List.of("email"));
            assertEquals(1L, query.table("users").count());
            assertEquals("A2", query.table("users").value("name").orElseThrow());
        } else {
            UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                    () -> query.table("users").upsert(rows, List.of("email")));
            assertTrue(refused.getMessage().contains(version));
            assertTrue(refused.getMessage().contains("3.24"));
            assertEquals(0L, query.table("users").count());
        }
    }

    @Test
    public void insertOrIgnoreWorksOnEverySqliteVersion() {
        query.table("users").insert(Map.of("email", "a@x", "name", "A"));

        int added = query.table("users").insertOrIgnore(List.of(Map.of("email", "a@x", "name", "Z"),
                Map.of("email", "b@x", "name", "B")));

        assertEquals(1, added);
        assertEquals(2L, query.table("users").count());
        assertEquals("A", query.table("users").where("email", "a@x").value("name").orElseThrow());
    }
}

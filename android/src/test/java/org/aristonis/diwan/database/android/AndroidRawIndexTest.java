package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.database.sqlite.SQLiteDatabase;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Raw indexes and temporary tables on a table rebuild, through the Android driver. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidRawIndexTest {

    private static final String RAW_INDEX = "users_email_lower";

    private SQLiteDatabase database;
    private Connection db;
    private Schema schema;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        schema = SQLiteSchema.of(db);
    }

    @After
    public void close() {
        database.close();
    }

    @Test
    public void rawIndexSurvivesRebuild() {
        schema.create("users", t -> {
            t.id();
            t.string("name");
            t.string("email");
            t.rawIndex("lower(email)", RAW_INDEX);
        });
        Object before = db.scalar("select sql from sqlite_master where name = ?", RAW_INDEX).orElseThrow();

        schema.table("users", t -> t.string("name", 100).change());

        assertEquals(before, db.scalar("select sql from sqlite_master where name = ?", RAW_INDEX).orElseThrow());
    }

    @Test
    public void temporaryTableChangeNeedingRebuildRefused() {
        db.transaction(tx -> {
            Schema inTransaction = SQLiteSchema.of(tx);
            inTransaction.create("scratch", t -> {
                t.id();
                t.string("label");
                t.temporary();
            });

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> inTransaction.table("scratch", t -> t.string("label", 100).change()));

            assertEquals("Changing a temporary table is not supported on SQLite", error.getMessage());
            return null;
        });
    }
}

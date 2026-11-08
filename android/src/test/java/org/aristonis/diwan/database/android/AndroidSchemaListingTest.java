package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.util.Arrays;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Listing and dropping tables through the Android driver, which ships SQLite 3.22 on API 28. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidSchemaListingTest {

    private Connection db;
    private Schema schema;

    @Before
    public void createLinkedTables() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        database.setForeignKeyConstraintsEnabled(true);
        db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        schema = SQLiteSchema.of(db);
        schema.create("users", t -> {
            t.id();
            t.string("name");
        });
        schema.create("posts", t -> {
            t.id();
            t.foreignId("user_id").constrained();
        });
        db.insert("insert into users (name) values ('ann')");
        db.insert("insert into posts (user_id) values (1)");
    }

    @Test
    public void getTablesListsUserTablesOnly() {
        assertEquals("Table names, sqlite_sequence left out", Arrays.asList("posts", "users"),
                schema.getTableListing());
        assertEquals("Schema of a table", "main", schema.getTables().get(0).schema());
    }

    @Test
    public void dropAllTablesDropsLinkedTablesAndKeepsForeignKeysOn() {
        schema.dropAllTables();

        assertTrue("No table is left", schema.getTables().isEmpty());
        assertEquals("Foreign keys are still enforced", 1,
                ((Number) db.scalar("pragma foreign_keys").orElseThrow()).intValue());
    }
}

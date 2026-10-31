package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The blog migrations the SQLite sandbox runs, through the Android driver. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidMigratorTest {

    private static Migration step(String name, Consumer<Schema> up, Consumer<Schema> down) {
        return new Migration() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void up(Schema schema) {
                up.accept(schema);
            }

            @Override
            public void down(Schema schema) {
                down.accept(schema);
            }
        };
    }

    static List<Migration> blog() {
        return Arrays.asList(
                step("2026_01_users", s -> s.create("users", t -> {
                    t.id();
                    t.string("name");
                    t.string("email").unique();
                    t.timestamps();
                }), s -> s.drop("users")),
                step("2026_02_posts", s -> s.create("posts", t -> {
                    t.id();
                    t.foreignId("user_id").constrained().cascadeOnDelete();
                    t.string("title");
                }), s -> s.drop("posts")),
                step("2026_03_rename", s -> s.table("posts", t -> t.renameColumn("title", "headline")),
                        s -> s.table("posts", t -> t.renameColumn("headline", "title"))),
                step("2026_04_widen", s -> s.table("posts", t -> t.string("headline", 500).change()),
                        s -> s.table("posts", t -> t.string("headline").change())));
    }

    @Test
    public void blogMigrationsRunRollBackAndRunAgain() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        database.setForeignKeyConstraintsEnabled(true);
        Connection db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        Schema schema = SQLiteSchema.of(db);
        Migrator migrator = Migrator.builder(schema).migrations(blog()).build();

        assertEquals(4, migrator.run().size());
        db.insert("insert into users (name, email) values ('ann', 'a@x')");
        db.insert("insert into posts (user_id, headline) values (1, 'one')");
        assertEquals(4, migrator.rollback().size());
        assertFalse(schema.hasTable("users"));
        assertEquals(4, migrator.run().size());
        assertTrue(schema.hasColumn("posts", "headline"));
        database.close();
    }
}

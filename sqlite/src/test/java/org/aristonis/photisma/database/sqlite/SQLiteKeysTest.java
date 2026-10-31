package org.aristonis.photisma.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.photisma.database.Connection;
import org.aristonis.photisma.database.QueryException;
import org.aristonis.photisma.database.UniqueConstraintViolationException;
import org.aristonis.photisma.database.schema.ForeignKeyInfo;
import org.aristonis.photisma.database.schema.IndexInfo;
import org.aristonis.photisma.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteKeysTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("keys.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        schema.create("users", table -> {
            table.id();
            table.string("email");
        });
    }

    private IndexInfo index(String table, String name) {
        for (IndexInfo index : schema.indexes(table)) {
            if (index.name().equals(name)) {
                return index;
            }
        }
        throw new AssertionError("no index " + name + " in " + schema.indexes(table));
    }

    private boolean hasIndex(String table, String name) {
        for (IndexInfo index : schema.indexes(table)) {
            if (index.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void compositeIndexesAndKeysAreCreatedWithLaravelNames() {
        schema.create("posts", table -> {
            table.id();
            table.foreignId("user_id").constrained().cascadeOnDelete();
            table.string("slug").unique();
            table.timestamp("created_at");
            table.index("user_id", "created_at");
            table.unique(Arrays.asList("user_id", "slug"), "one_slug_per_user");
        });

        assertEquals(Arrays.asList("user_id", "created_at"), index("posts", "posts_user_id_created_at_index").columns());
        assertFalse(index("posts", "posts_user_id_created_at_index").isUnique());
        assertTrue(index("posts", "one_slug_per_user").isUnique());
        assertTrue(index("posts", "posts_slug_unique").isUnique());
        assertEquals(Arrays.asList("id"), index("posts", "primary").columns());
        assertTrue(index("posts", "primary").isPrimary());
        assertEquals(Collections.singletonList(new ForeignKeyInfo(Arrays.asList("user_id"), "users",
                Arrays.asList("id"), "no action", "cascade")), schema.foreignKeys("posts"));
    }

    @Test
    void cascadeOnDeleteRemovesChildren() {
        schema.create("posts", table -> {
            table.id();
            table.foreignId("user_id").constrained().cascadeOnDelete();
        });
        db.insert("insert into users (id, email) values (1, 'a')");
        db.insert("insert into posts (user_id) values (1)");

        db.delete("delete from users where id = 1");

        assertEquals(0L, ((Number) db.scalar("select count(*) from posts").get()).longValue());
    }

    @Test
    void eachActionBehavesAsDeclared() {
        schema.create("restricted", table -> {
            table.foreignId("user_id").constrained().restrictOnDelete();
        });
        schema.create("nulled", table -> {
            table.foreignId("user_id").nullable().constrained().nullOnDelete().cascadeOnUpdate();
        });
        schema.create("plain", table -> table.foreignId("user_id").constrained().noActionOnDelete());
        db.insert("insert into users (id, email) values (1, 'a'), (2, 'b'), (3, 'c')");
        db.insert("insert into restricted (user_id) values (1)");
        db.insert("insert into nulled (user_id) values (2)");
        db.insert("insert into plain (user_id) values (3)");

        assertThrows(QueryException.class, () -> db.delete("delete from users where id = 1"));
        assertThrows(QueryException.class, () -> db.delete("delete from users where id = 3"));
        db.update("update users set id = 20 where id = 2");
        assertEquals(20L, ((Number) db.scalar("select user_id from nulled").get()).longValue());
        db.delete("delete from users where id = 20");
        assertFalse(db.scalar("select user_id from nulled").isPresent());
        assertThrows(QueryException.class, () -> db.insert("insert into plain (user_id) values (99)"));
    }

    @Test
    void aCompositePrimaryKeyIsWrittenIntoTheTable() {
        schema.create("tags", table -> {
            table.string("scope");
            table.string("code");
            table.primary("scope", "code");
        });
        db.insert("insert into tags values ('a', 'x')");

        assertThrows(UniqueConstraintViolationException.class, () -> db.insert("insert into tags values ('a', 'x')"));
        assertEquals(Arrays.asList("scope", "code"), index("tags", "primary").columns());
    }

    @Test
    void aPrimaryKeyBesideAnAutoIncrementColumnIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> schema.create("bad", table -> {
            table.id();
            table.primary("id");
        }));
    }

    @Test
    void indexesAreDroppedByNameOrColumnsAndRenamed() {
        schema.table("users", table -> {
            table.index("email");
            table.unique(Arrays.asList("email"), "email_u");
            table.index(Arrays.asList("id", "email"), "both");
        });

        schema.table("users", table -> {
            table.dropIndex(Arrays.asList("email"));
            table.dropUnique("email_u");
            table.renameIndex("both", "both_renamed");
        });

        assertFalse(hasIndex("users", "users_email_index"));
        assertFalse(hasIndex("users", "email_u"));
        assertEquals(Arrays.asList("id", "email"), index("users", "both_renamed").columns());

        schema.table("users", table -> table.dropIndex("both_renamed"));
        assertFalse(hasIndex("users", "both_renamed"));
    }

    @Test
    void droppingAForeignKeyByNameIsRefused() {
        schema.create("posts", table -> table.foreignId("user_id").constrained());

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> schema.table("posts", table -> table.dropForeign("posts_user_id_foreign")));
        assertTrue(thrown.getMessage().contains("dropForeign(List)"));
    }

    @Test
    void anIndexedColumnCannotBeDroppedButItsIndexFirstThenItCan() {
        schema.table("users", table -> table.index("email"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> schema.table("users", table -> table.dropColumn("email")));
        assertTrue(thrown.getMessage().contains("users_email_index"));

        schema.table("users", table -> {
            table.dropIndex(Arrays.asList("email"));
            table.dropColumn("email");
        });
        assertFalse(schema.hasColumn("users", "email"));
    }

    @Test
    void aColumnInAForeignKeyOrPrimaryKeyCannotBeDropped() {
        schema.create("posts", table -> {
            table.id();
            table.foreignId("user_id").constrained();
        });

        assertThrows(IllegalArgumentException.class, () -> schema.table("posts", table -> table.dropColumn("user_id")));
        assertThrows(IllegalArgumentException.class, () -> schema.table("posts", table -> table.dropColumn("id")));
    }

    @Test
    void introspectionOfAMissingTableIsEmpty() {
        assertTrue(schema.indexes("nothing").isEmpty());
        assertTrue(schema.foreignKeys("nothing").isEmpty());
        List<IndexInfo> users = schema.indexes("users");
        assertEquals(1, users.size());
    }

    @Test
    void versionsCompareByNumber() {
        assertTrue(SQLiteSchemaGrammar.atLeast("3.35.0", new int[] {3, 35, 0}));
        assertTrue(SQLiteSchemaGrammar.atLeast("3.45.1", new int[] {3, 35, 0}));
        assertTrue(SQLiteSchemaGrammar.atLeast("4", new int[] {3, 35, 0}));
        assertFalse(SQLiteSchemaGrammar.atLeast("3.22.0", new int[] {3, 25, 0}));
        assertFalse(SQLiteSchemaGrammar.atLeast("3.x", new int[] {3, 25, 0}));
    }
}

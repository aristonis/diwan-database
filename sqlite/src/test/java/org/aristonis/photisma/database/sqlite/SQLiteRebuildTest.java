package org.aristonis.photisma.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aristonis.photisma.database.Connection;
import org.aristonis.photisma.database.LoggedQuery;
import org.aristonis.photisma.database.QueryException;
import org.aristonis.photisma.database.UniqueConstraintViolationException;
import org.aristonis.photisma.database.schema.ForeignKeyCheckException;
import org.aristonis.photisma.database.schema.IndexInfo;
import org.aristonis.photisma.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteRebuildTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createBlog() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("blog.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        schema.create("users", table -> {
            table.id();
            table.string("email").unique();
        });
        schema.create("categories", table -> table.id());
        schema.create("posts", table -> {
            table.id();
            table.foreignId("user_id").constrained().cascadeOnDelete();
            table.integer("category_id").nullable();
            table.string("title");
            table.string("code").index();
        });
        schema.create("comments", table -> {
            table.id();
            table.foreignId("post_id").constrained().cascadeOnDelete();
        });
        db.insert("insert into users (id, email) values (1, 'a'), (2, 'b')");
        db.insert("insert into categories (id) values (7)");
        db.insert("insert into posts (id, user_id, category_id, title, code) values (1, 1, 7, 'one', 'c1'),"
                + " (2, 2, null, 'two', 'c2'), (3, 1, 7, 'three', 'c3')");
        db.insert("insert into comments (post_id) values (1), (1), (2)");
    }

    private long count(String table) {
        return ((Number) db.scalar("select count(*) from " + table).get()).longValue();
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
    void addingAForeignKeyToAFilledTableKeepsEveryRowAndThenEnforcesIt() {
        schema.table("posts", table -> table.foreign("category_id").references("id").on("categories"));

        assertEquals(3, count("posts"));
        assertEquals(3, count("comments"));
        assertEquals(2, schema.foreignKeys("posts").size());
        assertTrue(hasIndex("posts", "posts_code_index"));
        assertThrows(QueryException.class,
                () -> db.insert("insert into posts (user_id, category_id, title, code) values (1, 99, 'x', 'y')"));
    }

    @Test
    void aRowThatBreaksTheNewKeyUndoesTheWholeChange() {
        db.update("update posts set category_id = 99 where id = 2");

        ForeignKeyCheckException thrown = assertThrows(ForeignKeyCheckException.class,
                () -> schema.table("posts", table -> table.foreign("category_id").references("id").on("categories")));

        assertEquals("posts", thrown.table());
        assertEquals("categories", thrown.referencedTable());
        assertEquals(1, thrown.rowCount());
        assertFalse(thrown.getMessage().contains("99"));
        assertEquals(1, schema.foreignKeys("posts").size());
        assertEquals(3, count("posts"));
        assertEquals(1, ((Number) db.scalar("pragma foreign_keys").get()).intValue());
    }

    @Test
    void droppingAForeignKeyOfAReferencedTableKeepsItsChildren() {
        schema.table("posts", table -> table.dropForeign(Arrays.asList("user_id")));

        assertEquals(3, count("posts"));
        assertEquals(3, count("comments"));
        assertTrue(schema.foreignKeys("posts").isEmpty());
        assertEquals(1, schema.foreignKeys("comments").size());
        db.delete("delete from users where id = 1");
        assertEquals(3, count("posts"));
    }

    @Test
    void droppingThePrimaryKeyKeepsRowsIndexesAndUniqueConstraints() {
        schema.create("tags", table -> {
            table.string("code");
            table.string("label");
            table.primary("code");
        });
        db.statement("create table inline (code text unique, note text)");
        db.insert("insert into tags values ('a', 'A'), ('b', 'B')");
        db.insert("insert into inline values ('x', '1')");
        schema.table("tags", table -> table.index("label"));

        schema.table("tags", table -> table.dropPrimary());
        schema.table("inline", table -> table.primary("note"));

        assertEquals(2, count("tags"));
        assertFalse(schema.indexes("tags").stream().anyMatch(IndexInfo::isPrimary));
        assertTrue(hasIndex("tags", "tags_label_index"));
        db.insert("insert into tags values ('a', 'again')");
        assertThrows(UniqueConstraintViolationException.class, () -> db.insert("insert into inline values ('x', '2')"));
    }

    @Test
    void anAutoIncrementTableKeepsItsCounter() {
        db.delete("delete from posts where id = 3");

        schema.table("posts", table -> table.foreign("category_id").references("id").on("categories"));
        db.insert("insert into posts (user_id, title, code) values (1, 'next', 'c4')");

        assertEquals(4L, ((Number) db.scalar("select max(id) from posts").get()).longValue());
    }

    @Test
    void anEmptyAutoIncrementTableKeepsItsCounter() {
        db.delete("delete from comments");

        schema.table("comments", table -> table.dropForeign(Arrays.asList("post_id")));
        db.insert("insert into comments (post_id) values (1)");

        assertEquals(4L, ((Number) db.scalar("select max(id) from comments").get()).longValue());
    }

    @Test
    void aBlueprintThatAddsRenamesAndConstrainsKeepsAllData() {
        schema.table("posts", table -> {
            table.string("summary").nullable();
            table.renameColumn("title", "headline");
            table.foreign("category_id").references("id").on("categories");
            table.index("summary");
        });

        assertEquals(Arrays.asList("one", "two", "three"), column("posts", "headline"));
        assertTrue(schema.hasColumn("posts", "summary"));
        assertTrue(hasIndex("posts", "posts_summary_index"));
        assertEquals(2, schema.foreignKeys("posts").size());
    }

    @Test
    void olderSQLiteRenamesAndDropsColumnsByRebuilding() {
        Schema old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));

        old.table("posts", table -> {
            table.renameColumn("title", "headline");
            table.dropIndex(Arrays.asList("code"));
            table.dropColumn("code");
        });

        assertEquals(Arrays.asList("one", "two", "three"), column("posts", "headline"));
        assertFalse(old.hasColumn("posts", "code"));
        assertFalse(old.hasColumn("posts", "title"));
        assertEquals(3, count("comments"));
        assertEquals(1, old.foreignKeys("posts").size());
    }

    @Test
    void newerSQLiteRenamesAndDropsColumnsInPlace() {
        List<LoggedQuery> sql = schema.pretend(s -> s.table("posts", table -> {
            table.renameColumn("title", "headline");
            table.dropIndex(Arrays.asList("code"));
            table.dropColumn("code");
        }));

        assertEquals(Arrays.asList("alter table \"posts\" rename column \"title\" to \"headline\"",
                "drop index \"posts_code_index\"", "alter table \"posts\" drop column \"code\""), sqlOf(sql));
    }

    @Test
    void pretendListsTheRebuildAndChangesNothing() {
        List<LoggedQuery> sql = schema.pretend(s -> s.table("posts",
                table -> table.foreign("category_id").references("id").on("categories")));

        List<String> statements = sqlOf(sql);
        assertTrue(statements.get(0).startsWith("create table \"__temp__posts\""));
        assertTrue(statements.contains("drop table \"posts\""));
        assertTrue(statements.contains("alter table \"__temp__posts\" rename to \"posts\""));
        assertEquals(1, schema.foreignKeys("posts").size());
        assertEquals(1, ((Number) db.scalar("pragma foreign_keys").get()).intValue());
    }

    @Test
    void pretendOfATableThatDoesNotExistYetSaysSo() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> schema.pretend(s -> {
            s.create("later", table -> table.id());
            s.table("later", table -> table.dropPrimary());
        }));
        assertTrue(thrown.getMessage().contains("pretend"));
    }

    @Test
    void insideACallersTransactionAReferencedTableIsNotRebuilt() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            SQLiteSchema.of(tx).table("posts", table -> table.dropForeign(Arrays.asList("user_id")));
            return null;
        }));

        assertTrue(thrown.getMessage().contains("withoutForeignKeyConstraints"));
        assertEquals(3, count("comments"));
    }

    @Test
    void insideACallersTransactionAnUnreferencedTableIsRebuilt() {
        db.transaction(tx -> {
            SQLiteSchema.of(tx).table("comments", table -> table.dropForeign(Arrays.asList("post_id")));
            return null;
        });

        assertTrue(schema.foreignKeys("comments").isEmpty());
        assertEquals(3, count("comments"));
    }

    @Test
    void withoutForeignKeyConstraintsLetsAReferencedTableBeRebuilt() {
        schema.withoutForeignKeyConstraints(s -> s.table("posts", table -> table.dropForeign(Arrays.asList("user_id"))));

        assertEquals(3, count("comments"));
        assertEquals(1, ((Number) db.scalar("pragma foreign_keys").get()).intValue());
        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            SQLiteSchema.of(tx).withoutForeignKeyConstraints(s -> { });
            return null;
        }));
    }

    @Test
    void tablesWithFeaturesARebuildWouldLoseAreRefused() {
        db.statement("create table checked (n integer check (n > 0))");
        db.statement("create table triggered (n integer)");
        db.statement("create trigger t_ins after insert on triggered begin select 1; end");
        db.statement("create table viewed (n integer)");
        db.statement("create view v as select n from viewed");

        assertThrows(UnsupportedOperationException.class, () -> schema.table("checked", t -> t.primary("n")));
        assertThrows(UnsupportedOperationException.class, () -> schema.table("triggered", t -> t.primary("n")));
        assertThrows(UnsupportedOperationException.class, () -> schema.table("viewed", t -> t.primary("n")));
    }

    @Test
    void keyChangesThatDoNotFitTheTableAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> schema.table("posts", t -> t.primary("title")));
        assertThrows(IllegalArgumentException.class,
                () -> schema.table("posts", t -> t.dropForeign(Arrays.asList("title"))));
        assertThrows(IllegalArgumentException.class,
                () -> schema.table("posts", t -> t.foreign("nope").references("id").on("users")));
        assertThrows(IllegalArgumentException.class, () -> schema.table("posts", t -> t.renameIndex("nope", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> schema.table("categories", t -> {
                    t.dropPrimary();
                    t.dropPrimary();
                }));
    }

    @Test
    void olderSQLiteRefusesToRenameAColumnAnotherTableReferences() {
        Schema old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));

        assertThrows(UnsupportedOperationException.class, () -> old.table("users", t -> t.renameColumn("id", "uid")));
        assertEquals(2, count("users"));
    }

    @Test
    void viewsAndTriggersBlockOnlyTheTablesTheyName() {
        db.statement("create table user (n integer)");
        db.statement("create view all_users as select id from users");
        db.statement("create table audit (n integer)");
        db.statement("create trigger on_audit after insert on audit begin insert into user values (1); end");

        schema.table("posts", t -> t.foreign("category_id").references("id").on("categories"));
        assertThrows(UnsupportedOperationException.class, () -> schema.table("user", t -> t.primary("n")));
        assertThrows(UnsupportedOperationException.class, () -> schema.table("users", t -> t.dropPrimary()));
    }

    private List<Object> column(String table, String column) {
        List<Object> values = new java.util.ArrayList<>();
        for (Map<String, Object> row : db.select("select \"" + column + "\" as v from " + table + " order by id")) {
            values.add(row.get("v"));
        }
        return values;
    }

    private static List<String> sqlOf(List<LoggedQuery> queries) {
        List<String> sql = new java.util.ArrayList<>();
        for (LoggedQuery query : queries) {
            sql.add(query.sql());
        }
        return sql;
    }
}

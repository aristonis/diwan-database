package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.schema.ForeignKeyInfo;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class PostgresSchemaTest {

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createBlog() {
        db = PostgresServer.newDatabase();
        schema = PostgresSchema.of(db);
        schema.create("users", t -> {
            t.id();
            t.string("email").unique();
        });
        schema.create("posts", t -> {
            t.id();
            t.foreignId("user_id").constrained().cascadeOnDelete();
            t.string("title");
            t.string("code", 10).index();
            t.check("posts_title_check", "length(title) > 0");
        });
        db.insert("insert into users (email) values ('a'), ('b')");
        db.insert("insert into posts (user_id, title, code) values (1, 'one', '12'), (2, 'two', '34')");
    }

    private long count(String sql) {
        return ((Number) db.scalar(sql).get()).longValue();
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
    void schemaReadsReturnWhatTheDatabaseHolds() {
        assertTrue(schema.hasTable("posts"));
        assertFalse(schema.hasTable("nope"));
        assertTrue(schema.hasColumn("posts", "title"));
        assertTrue(hasIndex("users", "users_email_unique"));
        assertTrue(hasIndex("posts", "posts_code_index"));
        assertTrue(hasIndex("posts", "posts_pkey"));
        List<ForeignKeyInfo> keys = schema.foreignKeys("posts");
        assertEquals(1, keys.size());
        assertEquals(Arrays.asList("user_id"), keys.get(0).columns());
        assertEquals("users", keys.get(0).foreignTable());
        assertEquals("cascade", keys.get(0).onDelete());
        assertEquals("no action", keys.get(0).onUpdate());
    }

    @Test
    void aTableInAnotherSchemaIsNotSeen() {
        db.statement("create schema other");
        db.statement("create table other.hidden (id int)");

        assertFalse(schema.hasTable("hidden"));
    }

    @Test
    void foreignKeysEnforceAndCascade() {
        assertThrows(QueryException.class,
                () -> db.insert("insert into posts (user_id, title, code) values (99, 'x', 'y')"));
        db.delete("delete from users where id = 1");
        assertEquals(1, count("select count(*) from posts"));
    }

    @Test
    void dropsByNameAndByColumns() {
        schema.table("posts", t -> {
            t.dropForeign(Arrays.asList("user_id"));
            t.dropIndex("posts_code_index");
            t.dropCheck("posts_title_check");
        });
        schema.table("users", t -> {
            t.dropUnique(Arrays.asList("email"));
            t.dropPrimary();
        });

        assertTrue(schema.foreignKeys("posts").isEmpty());
        assertFalse(hasIndex("posts", "posts_code_index"));
        assertFalse(hasIndex("users", "users_email_unique"));
        assertFalse(hasIndex("users", "users_pkey"));
        db.insert("insert into posts (user_id, title, code) values (99, '', 'y')");
    }

    @Test
    void columnCommandsRunAndKeepRows() {
        schema.table("posts", t -> {
            t.string("summary").nullable().comment("short");
            t.renameColumn("title", "headline");
            t.renameIndex("posts_code_index", "posts_code_idx");
        });
        schema.table("posts", t -> {
            t.dropIndex("posts_code_idx");
            t.dropColumn("summary", "code");
        });

        assertTrue(schema.hasColumn("posts", "headline"));
        assertFalse(schema.hasColumn("posts", "code"));
        assertEquals(2, count("select count(*) from posts"));
    }

    @Test
    void aRenamedColumnStaysInItsCheck() {
        schema.table("posts", t -> t.renameColumn("title", "headline"));

        assertThrows(QueryException.class, () -> db.update("update posts set headline = '' where id = 1"));
    }

    @Test
    void changeConvertsTypesNullabilityAndDefaults() {
        schema.table("posts", t -> t.integer("code").nullable().defaultValue(7).change());

        assertEquals(12, ((Number) db.scalar("select code from posts where id = 1").get()).intValue());
        db.insert("insert into posts (user_id, title) values (1, 'three')");
        assertEquals(7, ((Number) db.scalar("select code from posts where title = 'three'").get()).intValue());
        db.update("update posts set code = null where id = 1");
    }

    @Test
    void aChangeThatCannotConvertLeavesTheTableAsItWas() {
        db.update("update posts set code = 'abc' where id = 2");

        assertThrows(QueryException.class, () -> schema.table("posts", t -> {
            t.string("title", 100).nullable().change();
            t.integer("code").change();
        }));

        assertEquals("character varying", db.scalar("select data_type from information_schema.columns where"
                + " table_name = 'posts' and column_name = 'code'").get());
        db.insert("insert into posts (user_id, title, code) values (1, 'x', 'abcd')");
    }

    @Test
    void changeRefusesWhatPostgresCannotAlter() {
        assertThrows(UnsupportedOperationException.class,
                () -> schema.table("posts", t -> t.enumColumn("code", Arrays.asList("a")).change()));
        assertThrows(UnsupportedOperationException.class,
                () -> schema.table("posts", t -> t.integer("code").storedAs("1").change()));
    }

    @Test
    void aFailingStatementLeavesNothingBehind() {
        assertThrows(QueryException.class, () -> schema.create("broken", t -> {
            t.id();
            t.integer("x");
            t.foreign("x").references("id").on("missing_table");
        }));

        assertFalse(schema.hasTable("broken"));
    }

    @Test
    void aLongGeneratedIndexNameIsRefusedBeforeSql() {
        assertThrows(IllegalArgumentException.class, () -> schema.table("posts",
                t -> t.index("title", "code", "user_id", "id", "title_once_more_to_pass_the_limit")));
    }

    @Test
    void dropPrimaryFindsTheKeyAfterARename() {
        schema.create("old_name", t -> t.id());
        schema.rename("old_name", "new_name");

        schema.table("new_name", t -> t.dropPrimary());

        assertFalse(hasIndex("new_name", "old_name_pkey"));
    }

    @Test
    void expressionIndexesShowTheirExpressionAndIncludeColumnsAreLeftOut() {
        db.statement("create index posts_lower_title on posts (lower(title), code)");
        db.statement("create unique index posts_code_incl on posts (code) include (title)");

        for (IndexInfo index : schema.indexes("posts")) {
            if (index.name().equals("posts_lower_title")) {
                assertTrue(index.columns().get(0).startsWith("lower(title"), index.columns().toString());
                assertEquals("code", index.columns().get(1));
            }
            if (index.name().equals("posts_code_incl")) {
                assertEquals(Arrays.asList("code"), index.columns());
            }
        }
        assertTrue(hasIndex("posts", "posts_lower_title"));
    }

    @Test
    void aSearchPathWithoutASchemaIsReportedNotTreatedAsEmpty() {
        String database = String.valueOf(db.scalar("select current_database()").get());
        PGSimpleDataSource source = PostgresServer.dataSource(database);
        source.setCurrentSchema("missing_schema");
        Schema lost = PostgresSchema.of(Connection.builder().name("pgsql").dataSource(source).build());

        assertThrows(IllegalStateException.class, () -> lost.create("lost", t -> t.id()));
    }

    @Test
    void tablesAreRenamedAndDropped() {
        schema.create("tmp", t -> t.id());
        schema.rename("tmp", "tmp2");
        assertTrue(schema.hasTable("tmp2"));
        schema.drop("tmp2");
        schema.dropIfExists("tmp2");
        assertFalse(schema.hasTable("tmp2"));
    }
}

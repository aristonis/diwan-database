package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.schema.ForeignKeySource;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostgresBlueprintExtrasTest {

    private Connection db;
    private Schema schema;

    @BeforeEach
    void open() {
        db = PostgresServer.newDatabase();
        schema = PostgresSchema.of(db);
    }

    private static List<String> sqlOf(List<LoggedQuery> queries) {
        List<String> sql = new ArrayList<>();
        for (LoggedQuery query : queries) {
            sql.add(query.sql());
        }
        return sql;
    }

    private boolean hasIndex(String table, String name) {
        for (IndexInfo index : schema.indexes(table)) {
            if (index.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private String indexDef(String name) {
        return String.valueOf(db.scalar("select indexdef from pg_indexes where indexname = ?", name).orElse(""));
    }

    private String tableComment(String table) {
        return db.scalar("select obj_description(?::regclass)", table).map(String::valueOf).orElse(null);
    }

    @Test
    void temporaryTableIsCreatedAndGoneOnAnotherSession() {
        Connection other = PostgresServer.newDatabase();
        List<String> sql = sqlOf(schema.pretend(s -> s.create("scratch", t -> {
            t.temporary();
            t.id();
        })));
        assertTrue(sql.get(0).startsWith("create temporary table \"scratch\" ("), sql.get(0));

        Object persistence = db.session(session -> {
            PostgresSchema.of(session).create("scratch", t -> {
                t.temporary();
                t.id();
            });
            return session.scalar("select relpersistence from pg_class where relname = 'scratch'").orElse(null);
        });

        assertEquals("t", String.valueOf(persistence));
        assertFalse(other.scalar("select 1 from pg_class where relname = 'scratch'").isPresent());
    }

    @Test
    void rawIndexIsCreatedListedAndDropped() {
        schema.create("users", t -> {
            t.id();
            t.string("email");
            t.rawIndex("lower(email)", "users_email_lower");
        });

        assertTrue(indexDef("users_email_lower").contains("lower("), indexDef("users_email_lower"));
        assertTrue(hasIndex("users", "users_email_lower"));

        schema.table("users", t -> t.dropIndex("users_email_lower"));

        assertFalse(hasIndex("users", "users_email_lower"));
    }

    @Test
    void partialRawIndex() {
        schema.create("people", t -> {
            t.id();
            t.string("email");
            t.string("deleted_at").nullable();
            t.rawIndex("lower(email)", "people_email_open").whereNull("deleted_at");
        });

        String def = indexDef("people_email_open");
        assertTrue(def.contains("lower("), def);
        assertTrue(def.contains("deleted_at IS NULL"), def);
        assertTrue(hasIndex("people", "people_email_open"));
    }

    @Test
    void tableCommentEscapesQuotes() {
        schema.create("notes", t -> {
            t.id();
            t.comment("it's a note");
        });

        assertEquals("it's a note", tableComment("notes"));
    }

    @Test
    void tableCommentOnChangeReplacesAndEmptyRemoves() {
        schema.create("memos", t -> {
            t.id();
            t.comment("first");
        });

        schema.table("memos", t -> t.comment("second"));
        assertEquals("second", tableComment("memos"));

        schema.table("memos", t -> t.comment(""));
        assertNull(tableComment("memos"));
    }

    @Test
    void tableCommentStatementText() {
        List<String> sql = sqlOf(schema.pretend(s -> s.table("memos", t -> t.comment("it's"))));

        assertEquals(Arrays.asList("comment on table \"memos\" is 'it''s'"), sql);
    }

    @Test
    void foreignIdForConstrainedPointsAtTheSourceKey() {
        schema.create("members", t -> t.bigInteger("member_key").primary());
        ForeignKeySource source = new ForeignKeySource() {
            @Override
            public String foreignKey() {
                return "owner_ref";
            }

            @Override
            public String table() {
                return "members";
            }

            @Override
            public String keyName() {
                return "member_key";
            }

            @Override
            public KeyKind keyKind() {
                return KeyKind.INTEGER;
            }
        };

        schema.create("posts", t -> {
            t.id();
            t.foreignIdFor(source).constrained();
        });

        assertEquals(1, schema.foreignKeys("posts").size());
        assertEquals("members", schema.foreignKeys("posts").get(0).foreignTable());
        assertEquals(Arrays.asList("member_key"), schema.foreignKeys("posts").get(0).foreignColumns());
    }
}

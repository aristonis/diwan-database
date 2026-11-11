package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostgresPartialIndexTest {

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

    private String indexDef(String name) {
        return String.valueOf(db.scalar("select indexdef from pg_indexes where indexname = ?", name).orElse(""));
    }

    private IndexInfo info(String table, String name) {
        for (IndexInfo index : schema.indexes(table)) {
            if (index.name().equals(name)) {
                return index;
            }
        }
        throw new AssertionError("no index " + name);
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
    void partialIndexIsCreatedWithItsWhere() {
        schema.create("docs", t -> {
            t.id();
            t.string("status");
            t.string("deleted_at").nullable();
            t.index(Arrays.asList("status"), "docs_open").where("status", "o'pen").whereNull("deleted_at");
        });

        String def = indexDef("docs_open");
        assertTrue(def.contains("WHERE"), def);
        assertTrue(def.contains("'o''pen'"), def);
        assertTrue(def.contains("deleted_at IS NULL"), def);
    }

    @Test
    void booleanConditionIsWrittenAsTrue() {
        schema.create("flags", t -> {
            t.id();
            t.booleanColumn("active");
        });

        List<String> sql = sqlOf(schema.pretend(s -> s.table("flags",
                t -> t.index(Arrays.asList("id"), "flags_active").where("active", true))));
        schema.table("flags", t -> t.index(Arrays.asList("id"), "flags_active").where("active", true));

        assertEquals("create index \"flags_active\" on \"flags\" (\"id\") where \"active\" = true", sql.get(0));
        assertTrue(indexDef("flags_active").contains("active"), indexDef("flags_active"));
    }

    @Test
    void partialUniqueRefusesDuplicatesOnlyInsideThePredicate() {
        schema.create("slots", t -> {
            t.id();
            t.string("code");
            t.booleanColumn("live");
            t.unique(Arrays.asList("code"), "slots_code_live").where("live", true);
        });
        db.insert("insert into slots (code, live) values ('a', false), ('a', false), ('a', true)");

        assertThrows(QueryException.class, () -> db.insert("insert into slots (code, live) values ('a', true)"));
        assertEquals(3L, ((Number) db.scalar("select count(*) from slots").get()).longValue());
    }

    @Test
    void dropUniqueDropsAPartialUniqueIndex() {
        schema.create("slots", t -> {
            t.id();
            t.string("code");
            t.unique(Arrays.asList("code"), "slots_code_live").whereNotNull("code");
        });
        assertTrue(hasIndex("slots", "slots_code_live"));

        schema.table("slots", t -> t.dropUnique("slots_code_live"));

        assertFalse(hasIndex("slots", "slots_code_live"));
    }

    @Test
    void dropUniqueStillDropsAUniqueConstraint() {
        schema.create("users", t -> {
            t.id();
            t.string("email").unique();
        });
        assertTrue(hasIndex("users", "users_email_unique"));

        schema.table("users", t -> t.dropUnique("users_email_unique"));

        assertFalse(hasIndex("users", "users_email_unique"));
        assertEquals(0L, ((Number) db.scalar("select count(*) from pg_constraint where conname = 'users_email_unique'").get()).longValue());
    }

    @Test
    void renameIndexWorksForBothKinds() {
        schema.create("pairs", t -> {
            t.id();
            t.string("a");
            t.string("b");
            t.unique(Arrays.asList("a"), "pairs_a_unique");
            t.unique(Arrays.asList("b"), "pairs_b_part").whereNotNull("b");
        });

        schema.table("pairs", t -> {
            t.renameIndex("pairs_a_unique", "pairs_a_renamed");
            t.renameIndex("pairs_b_part", "pairs_b_renamed");
        });

        assertTrue(hasIndex("pairs", "pairs_a_renamed"));
        assertTrue(hasIndex("pairs", "pairs_b_renamed"));
        schema.table("pairs", t -> t.dropUnique("pairs_a_renamed"));
        assertFalse(hasIndex("pairs", "pairs_a_renamed"));
    }

    @Test
    void descendingAndCollatedIndexIsWritten() {
        schema.create("names", t -> {
            t.id();
            t.string("name");
        });

        List<String> sql = sqlOf(schema.pretend(s -> s.table("names",
                t -> t.index(Arrays.asList("name", "id"), "names_sorted").collate("name", "C").desc("id"))));
        schema.table("names", t -> t.index(Arrays.asList("name", "id"), "names_sorted").collate("name", "C").desc("id"));

        assertEquals("create index \"names_sorted\" on \"names\" (\"name\" collate \"C\", \"id\" desc)", sql.get(0));
        assertTrue(indexDef("names_sorted").contains("DESC"), indexDef("names_sorted"));
    }

    @Test
    void indexesReportWhereOrderAndCollation() {
        schema.create("names", t -> {
            t.id();
            t.string("name");
            t.booleanColumn("live");
            t.index(Arrays.asList("name", "id"), "names_sorted").collate("name", "C").desc("id").where("live", true);
        });

        IndexInfo index = info("names", "names_sorted");

        assertEquals(Arrays.asList("name", "id"), index.columns());
        assertEquals(Arrays.asList("asc", "desc"), index.orders());
        assertEquals(Arrays.asList("C", ""), index.collations());
        assertTrue(index.where().isPresent());
        assertTrue(index.where().get().contains("live"), index.where().get());
        assertFalse(info("names", "names_pkey").where().isPresent());
    }

    @Test
    void indexOnColumnWithItsOwnCollationReportsNone() {
        schema.create("tags", t -> {
            t.id();
            t.string("tag").collation("C");
            t.index("tag");
        });

        assertEquals(Arrays.asList(""), info("tags", "tags_tag_index").collations());
    }

    @Test
    void dropUniqueInPretendModePrintsTheDoBlock() {
        schema.create("docs", t -> t.id());

        List<String> sql = sqlOf(schema.pretend(s -> s.table("docs", t -> t.dropUnique("docs_x_unique"))));

        assertEquals("do $diwan$ begin if exists (select 1 from pg_constraint where conname = 'docs_x_unique'"
                + " and conrelid = to_regclass('\"docs\"')) then alter table \"docs\" drop constraint \"docs_x_unique\";"
                + " else drop index \"docs_x_unique\"; end if; end $diwan$", sql.get(0));
    }

    @Test
    void dropUniqueResolvesAMixedCaseTable() {
        schema.create("MixedDocs", t -> {
            t.id();
            t.string("code");
            t.unique(Arrays.asList("code"), "MixedDocs_code_unique");
            t.unique(Arrays.asList("id"), "MixedDocs_id_part").whereNotNull("id");
        });

        schema.table("MixedDocs", t -> {
            t.dropUnique("MixedDocs_code_unique");
            t.dropUnique("MixedDocs_id_part");
        });

        assertFalse(hasIndex("MixedDocs", "MixedDocs_code_unique"));
        assertFalse(hasIndex("MixedDocs", "MixedDocs_id_part"));
    }

    @Test
    void dropUniqueWithDollarsInTheNameStaysOneStatement() {
        schema.create("cash", t -> {
            t.id();
            t.string("code");
            t.unique(Arrays.asList("code"), "cash$$code$diwan$");
        });

        List<String> sql = sqlOf(schema.pretend(s -> s.table("cash", t -> t.dropUnique("cash$$code$diwan$"))));
        schema.table("cash", t -> t.dropUnique("cash$$code$diwan$"));

        assertTrue(sql.get(0).startsWith("do $diwan1$ "), sql.get(0));
        assertTrue(sql.get(0).endsWith(" $diwan1$"), sql.get(0));
        assertFalse(hasIndex("cash", "cash$$code$diwan$"));
    }
}

package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Partial indexes on SQLite: creation, the rebuild that keeps them, and reading them back. */
class SQLitePartialIndexTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;
    private Schema old;

    @BeforeEach
    void connect() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("partial.db"));
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));
    }

    private IndexInfo index(String table, String name) {
        for (IndexInfo index : schema.indexes(table)) {
            if (index.name().equals(name)) {
                return index;
            }
        }
        throw new AssertionError("no index " + name + " in " + schema.indexes(table));
    }

    private String indexSql(String name) {
        return String.valueOf(db.scalar("select sql from sqlite_master where type = 'index' and name = ?", name)
                .orElseThrow());
    }

    private void createRuns() {
        schema.create("runs", t -> {
            t.id();
            t.string("outcome");
            t.string("owner").nullable();
            t.string("code");
            t.booleanColumn("done").defaultValue(false);
        });
    }

    private long partialFlag(String index) {
        return ((Number) db.select("select partial from pragma_index_list('runs') where name = ?", index).get(0)
                .get("partial")).longValue();
    }

    @Test
    void whereOnAnIndexMakesItPartialWithTheConditionInItsSql() {
        createRuns();

        schema.table("runs", t -> t.index(Arrays.asList("outcome"), "runs_started").where("outcome", "STARTED"));

        assertEquals(1, partialFlag("runs_started"));
        assertTrue(indexSql("runs_started").contains("where \"outcome\" = 'STARTED'"), indexSql("runs_started"));
    }

    @Test
    void aQuoteInTheValueIsEscaped() {
        createRuns();

        schema.table("runs", t -> t.index(Arrays.asList("owner"), "runs_owner").where("owner", "O'Brien"));

        assertTrue(indexSql("runs_owner").contains("where \"owner\" = 'O''Brien'"), indexSql("runs_owner"));
        assertEquals("owner = 'O''Brien'".replace("owner", "\"owner\""), index("runs", "runs_owner").where().get());
    }

    @Test
    void aBooleanValueIsWrittenAsOne() {
        createRuns();

        schema.table("runs", t -> t.index(Arrays.asList("code"), "runs_done").where("done", true));

        assertTrue(indexSql("runs_done").contains("where \"done\" = 1"), indexSql("runs_done"));
    }

    @Test
    void equalityNullAndNotNullConditionsCombineWithAnd() {
        createRuns();

        schema.table("runs", t -> t.index(Arrays.asList("code"), "runs_mixed").where("outcome", "OK").whereNull("owner")
                .whereNotNull("code"));

        String sql = indexSql("runs_mixed");
        assertTrue(sql.contains("where \"outcome\" = 'OK' and \"owner\" is null and \"code\" is not null"), sql);
    }

    @Test
    void aPartialUniqueIndexRefusesDuplicatesOnlyInsideTheCondition() {
        createRuns();
        schema.table("runs", t -> t.unique(Arrays.asList("code"), "runs_code_started").where("outcome", "STARTED"));
        db.insert("insert into runs (outcome, code) values ('DONE', 'a')");
        db.insert("insert into runs (outcome, code) values ('DONE', 'a')");
        db.insert("insert into runs (outcome, code) values ('STARTED', 'a')");

        assertThrows(QueryException.class, () -> db.insert("insert into runs (outcome, code) values ('STARTED', 'a')"));
    }

    @Test
    void aRebuildKeepsThePartialIndexAndItsCondition() {
        createRuns();
        schema.table("runs", t -> t.index(Arrays.asList("code"), "runs_started").where("outcome", "STARTED"));

        schema.table("runs", t -> t.string("code", 50).change());

        assertEquals(1, partialFlag("runs_started"));
        assertTrue(indexSql("runs_started").contains("where \"outcome\" = 'STARTED'"), indexSql("runs_started"));
    }

    @Test
    void aRebuildKeepsAPartialIndexAddedInTheSameBlueprint() {
        createRuns();

        schema.table("runs", t -> {
            t.string("code", 50).change();
            t.index(Arrays.asList("code"), "runs_late").where("outcome", "STARTED");
        });

        assertEquals(1, partialFlag("runs_late"));
        assertTrue(indexSql("runs_late").contains("where \"outcome\" = 'STARTED'"), indexSql("runs_late"));
    }

    @Test
    void indexesReportsTheCondition() {
        createRuns();
        schema.table("runs", t -> {
            t.index(Arrays.asList("code"), "runs_started").where("outcome", "STARTED");
            t.index(Arrays.asList("owner"), "runs_plain");
        });

        assertEquals("\"outcome\" = 'STARTED'", index("runs", "runs_started").where().get());
        assertFalse(index("runs", "runs_plain").where().isPresent());
    }

    @Test
    void renamingAColumnInTheConditionWorksNativelyAndTheIndexStaysValid() {
        createRuns();
        schema.table("runs", t -> t.index(Arrays.asList("code"), "runs_started").where("outcome", "STARTED"));

        schema.table("runs", t -> t.renameColumn("outcome", "state"));

        assertTrue(index("runs", "runs_started").where().get().contains("state"));
        db.insert("insert into runs (state, code) values ('STARTED', 'x')");
        assertEquals(1, db.select("select * from runs indexed by runs_started where state = 'STARTED'").size());
    }

    @Test
    void renamingAColumnInTheConditionOnTheRebuildPathIsRefused() {
        createRuns();
        schema.table("runs", t -> t.index(Arrays.asList("code"), "runs_started").where("outcome", "STARTED"));

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> old.table("runs", t -> t.renameColumn("outcome", "state")));

        assertTrue(error.getMessage().contains("runs_started"), error.getMessage());
        assertTrue(error.getMessage().contains("outcome"), error.getMessage());
    }

    @Test
    void droppingAColumnInTheConditionOnTheRebuildPathIsRefused() {
        createRuns();
        schema.table("runs", t -> t.index(Arrays.asList("code"), "runs_started").where("outcome", "STARTED"));

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> old.table("runs", t -> t.dropColumn("outcome")));

        assertTrue(error.getMessage().contains("runs_started"), error.getMessage());
        assertTrue(error.getMessage().contains("outcome"), error.getMessage());
    }

    @Test
    void aConditionStringThatContainsTheColumnNameDoesNotBlockTheRename() {
        createRuns();
        schema.table("runs", t -> t.index(Arrays.asList("code"), "runs_literal").where("owner", "outcome"));

        old.table("runs", t -> t.renameColumn("outcome", "state"));

        assertEquals("\"owner\" = 'outcome'", index("runs", "runs_literal").where().get());
        assertEquals(1, partialFlag("runs_literal"));
    }

    @Test
    void aCommentInARawPartialIndexIsNotCarriedIntoTheWhere() {
        db.statement("create table notes (id integer primary key, body text, state text)");
        db.statement("create index notes_open on notes (id) where state = 'open)' /* keep */ -- end");

        assertEquals("state = 'open)'", index("notes", "notes_open").where().orElseThrow());

        schema.table("notes", t -> t.string("body", 50).change());

        assertEquals("state = 'open)'", index("notes", "notes_open").where().orElseThrow());
    }
}

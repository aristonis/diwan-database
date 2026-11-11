package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Descending columns and collations on SQLite indexes, in the DDL, through a rebuild and when read back. */
class SQLiteIndexOrderTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;
    private Schema old;

    @BeforeEach
    void connect() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("order.db"));
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

    private void createPeople() {
        schema.create("people", t -> {
            t.id();
            t.string("name");
            t.string("tag").collation("nocase");
        });
    }

    private Map<String, Object> xinfo(String index, String column) {
        for (Map<String, Object> row : db.select(
                "select name, \"desc\", coll from pragma_index_xinfo(?) where key = 1", index)) {
            if (column.equals(row.get("name"))) {
                return row;
            }
        }
        throw new AssertionError(column);
    }

    @Test
    void descAndCollateAreWrittenAndSQLiteReportsThem() {
        createPeople();

        schema.table("people", t -> t.index(Arrays.asList("id", "name"), "people_order").desc("id").collate("name", "nocase"));

        String sql = indexSql("people_order");
        assertTrue(sql.contains("\"id\" desc"), sql);
        assertTrue(sql.contains("\"name\" collate"), sql);
        assertEquals(1L, ((Number) xinfo("people_order", "id").get("desc")).longValue());
        assertEquals("NOCASE", String.valueOf(xinfo("people_order", "name").get("coll")).toUpperCase());
        IndexInfo info = index("people", "people_order");
        assertEquals(Arrays.asList("desc", "asc"), info.orders());
        assertEquals(Arrays.asList("", "NOCASE"), upper(info.collations()));
    }

    private static List<String> upper(List<String> names) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String name : names) {
            result.add(name.toUpperCase());
        }
        return result;
    }

    @Test
    void aRebuildKeepsTheOrderAndTheCollation() {
        createPeople();
        schema.table("people", t -> t.index(Arrays.asList("id", "name"), "people_order").desc("id").collate("name", "nocase"));

        schema.table("people", t -> t.string("name", 80).change());

        assertEquals(1L, ((Number) xinfo("people_order", "id").get("desc")).longValue());
        assertEquals("NOCASE", String.valueOf(xinfo("people_order", "name").get("coll")).toUpperCase());
    }

    @Test
    void aRebuildKeepsAnOrderedIndexAddedInTheSameBlueprint() {
        createPeople();

        schema.table("people", t -> {
            t.string("name", 80).change();
            t.index(Arrays.asList("id", "name"), "people_late").desc("id");
        });

        assertEquals(1L, ((Number) xinfo("people_late", "id").get("desc")).longValue());
    }

    @Test
    void anIndexWithoutOptionsKeepsItsDefaultName() {
        createPeople();

        schema.table("people", t -> t.index("name"));

        assertEquals("people_name_index", index("people", "people_name_index").name());
        assertEquals(Collections.singletonList("asc"), index("people", "people_name_index").orders());
        assertEquals(Collections.singletonList(""), index("people", "people_name_index").collations());
    }

    @Test
    void aColumnsOwnCollationIsNotReportedAsTheIndexCollation() {
        createPeople();

        schema.table("people", t -> t.index(Arrays.asList("tag"), "people_tag"));

        assertEquals(Collections.singletonList(""), index("people", "people_tag").collations());
    }
}

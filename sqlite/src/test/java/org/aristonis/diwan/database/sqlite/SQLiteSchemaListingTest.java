package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.schema.TableInfo;
import org.aristonis.diwan.database.schema.ViewInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Listing and dropping tables and views on SQLite, with foreign keys enforced. */
class SQLiteSchemaListingTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        db = connection();
        schema = SQLiteSchema.of(db);
    }

    private Connection connection() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("listing.db") + "?foreign_keys=true");
        return Connection.builder().name("sqlite").dataSource(dataSource).build();
    }

    private void createLinkedTables() {
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

    private int pragma(String name) {
        return ((Number) db.scalar("pragma " + name).orElseThrow()).intValue();
    }

    @Test
    void getTablesListsUserTablesOnlyOrderedByName() {
        createLinkedTables();

        assertEquals(Arrays.asList(new TableInfo("posts", "main"), new TableInfo("users", "main")),
                schema.getTables());
        assertEquals(Arrays.asList("posts", "users"), schema.getTableListing());
    }

    @Test
    void getTablesFallsBackToSqliteMasterOnAnOldVersion() {
        createLinkedTables();
        Schema old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));

        assertEquals(Arrays.asList("posts", "users"), old.getTableListing());
        assertEquals("main", old.getTables().get(0).schema());
    }

    @Test
    void getViewsListsViewsOnly() {
        createLinkedTables();
        db.statement("create view user_names as select name from users");

        assertEquals(Collections.singletonList(new ViewInfo("user_names", "main")), schema.getViews());
        assertFalse(schema.getTableListing().contains("user_names"));
    }

    @Test
    void dropAllTablesDropsLinkedTablesAndKeepsForeignKeysOn() {
        createLinkedTables();

        schema.dropAllTables();

        assertTrue(schema.getTables().isEmpty());
        assertEquals(1, pragma("foreign_keys"));
        assertEquals(0, pragma("defer_foreign_keys"));
    }

    @Test
    void dropAllViewsDropsEveryView() {
        createLinkedTables();
        db.statement("create view a_view as select name from users");
        db.statement("create view b_view as select user_id from posts");

        schema.dropAllViews();

        assertTrue(schema.getViews().isEmpty());
        assertEquals(2, schema.getTables().size());
    }

    @Test
    void aFailureMidwayRollsBackAndRestoresTheSettings() {
        createLinkedTables();
        Schema failing = Schema.of(db, new SQLiteSchemaGrammar() {
            @Override
            public List<String> compileDropAllTables(List<TableInfo> tables) {
                List<String> statements = new ArrayList<>(super.compileDropAllTables(tables));
                statements.add("drop table \"missing_table\"");
                return statements;
            }
        });

        assertThrows(QueryException.class, failing::dropAllTables);

        assertEquals(Arrays.asList("posts", "users"), schema.getTableListing());
        assertEquals(1, pragma("foreign_keys"));
        assertEquals(0, pragma("defer_foreign_keys"));
    }

    @Test
    void dropAllTablesJoinsAnOpenTransaction() {
        createLinkedTables();

        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            Schema.of(tx, new SQLiteSchemaGrammar()).dropAllTables();
            throw new IllegalStateException("undo");
        }));

        assertEquals(Arrays.asList("posts", "users"), schema.getTableListing());
    }

    @Test
    void dropAllOnAnEmptyDatabaseRunsNoStatement() {
        List<String> sql = new ArrayList<>();
        schema.pretend(s -> {
            s.dropAllViews();
            s.dropAllTables();
        }).forEach(q -> sql.add(q.sql()));

        assertTrue(sql.isEmpty(), sql.toString());
        schema.dropAllTables();
        schema.dropAllViews();
        assertTrue(schema.getTables().isEmpty());
    }

    @Test
    void freshDropsEverythingIncludingForeignTablesAndRerunsMigrations() {
        Migration createUsers = ManagerFixtures.createUsers();
        Migrator migrator = Migrator.builder(schema).migrations(Collections.singletonList(createUsers)).build();
        migrator.run();
        db.statement("create table extra (id integer primary key)");
        db.statement("create view user_names as select name from users");
        db.insert("insert into users (name) values ('ann')");

        assertEquals(1, migrator.fresh().size());

        assertEquals(Arrays.asList("migrations", "users"), schema.getTableListing());
        assertTrue(schema.getViews().isEmpty());
        assertEquals(0L, ((Number) db.scalar("select count(*) from users").orElseThrow()).longValue());
    }

    @Test
    void dropAllTablesOrdersChildrenFirstSoARestrictKeyDoesNotBlockTheDrop() {
        schema.create("a_parent", t -> t.id());
        schema.create("b_child", t -> {
            t.id();
            t.foreignId("parent_id").constrained("a_parent").restrictOnDelete();
        });
        db.insert("insert into a_parent default values");
        db.insert("insert into b_child (parent_id) values (1)");

        schema.dropAllTables();

        assertTrue(schema.getTables().isEmpty());
    }

    @Test
    void dropAllTablesDropsAVirtualTableWithItsShadowTables() {
        db.statement("create virtual table docs using fts5(body)");
        db.statement("insert into docs (body) values ('hello')");
        schema.create("plain", t -> t.id());

        schema.dropAllTables();

        assertTrue(schema.getTables().isEmpty());
        assertEquals(0L, ((Number) db.scalar("select count(*) from sqlite_master where name like 'docs%'")
                .orElseThrow()).longValue());
    }

    @Test
    void theOldVersionListingLeavesOutShadowTablesButKeepsOtherTables() {
        db.statement("create virtual table docs using fts5(body)");
        db.statement("create table docs_log (id integer primary key)");
        Schema old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));

        List<String> names = old.getTableListing();

        assertEquals(Arrays.asList("docs", "docs_log"), names);
        old.dropAllTables();
        assertTrue(schema.getTables().isEmpty());
    }

    @Test
    void aPrefixedConnectionListsRealNamesAndDropsThemOnce() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("prefixed.db") + "?foreign_keys=true");
        Connection prefixed = Connection.builder().name("prefixed").dataSource(dataSource).tablePrefix("app_").build();
        Schema app = SQLiteSchema.of(prefixed);
        app.create("users", t -> t.id());
        app.create("posts", t -> {
            t.id();
            t.foreignId("user_id").constrained();
        });
        prefixed.statement("create view app_names as select id from app_users");

        assertEquals(Arrays.asList("app_posts", "app_users"), app.getTableListing());
        app.dropAllTables();
        app.dropAllViews();

        assertTrue(app.getTables().isEmpty());
        assertTrue(app.getViews().isEmpty());
    }
}

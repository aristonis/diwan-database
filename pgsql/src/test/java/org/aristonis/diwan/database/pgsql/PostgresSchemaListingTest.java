package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.schema.TableInfo;
import org.aristonis.diwan.database.schema.ViewInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Listing and dropping tables and views on PostgreSQL. */
class PostgresSchemaListingTest {

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        db = PostgresServer.newDatabase();
        schema = PostgresSchema.of(db);
    }

    private void createLinkedTables() {
        schema.create("users", t -> {
            t.id();
            t.string("email");
        });
        schema.create("posts", t -> {
            t.id();
            t.foreignId("user_id").constrained();
        });
        db.insert("insert into users (email) values ('a')");
        db.insert("insert into posts (user_id) values (1)");
    }

    /** Opens a connection to the same database whose search path holds {@code public} and {@code extra}. */
    private Connection withExtraSchema() {
        db.statement("create schema extra");
        PGSimpleDataSource source = PostgresServer.dataSource(
                String.valueOf(db.scalar("select current_database()").orElseThrow()));
        source.setCurrentSchema("public,extra");
        return Connection.builder().name("pgsql").dataSource(source).build();
    }

    @Test
    void getTablesListsTablesOfEverySearchPathSchemaOrdered() {
        Connection both = withExtraSchema();
        Schema wide = PostgresSchema.of(both);
        createLinkedTables();
        both.statement("create table extra.audit (id int)");
        both.statement("create schema other");
        both.statement("create table other.hidden (id int)");

        assertEquals(Arrays.asList(new TableInfo("audit", "extra"), new TableInfo("posts", "public"),
                new TableInfo("users", "public")), wide.getTables());
        assertEquals(Arrays.asList("audit", "posts", "users"), wide.getTableListing());
    }

    @Test
    void getViewsListsViewsOnly() {
        createLinkedTables();
        db.statement("create view user_emails as select email from users");

        assertEquals(Collections.singletonList(new ViewInfo("user_emails", "public")), schema.getViews());
        assertTrue(schema.getTables().stream().noneMatch(t -> t.name().equals("user_emails")));
    }

    @Test
    void dropAllTablesRunsOneStatementKeepsSpatialRefSysAndDropsLinkedTables() {
        createLinkedTables();
        db.statement("create table spatial_ref_sys (id int)");

        List<LoggedQuery> recorded = schema.pretend(Schema::dropAllTables);
        schema.dropAllTables();

        assertEquals(1, recorded.size());
        assertTrue(recorded.get(0).sql().startsWith("drop table "), recorded.get(0).sql());
        assertTrue(recorded.get(0).sql().endsWith(" cascade"), recorded.get(0).sql());
        assertEquals(Collections.singletonList("spatial_ref_sys"), schema.getTableListing());
    }

    @Test
    void dropAllTablesAndViewsCoverEverySearchPathSchema() {
        Connection both = withExtraSchema();
        Schema wide = PostgresSchema.of(both);
        createLinkedTables();
        both.statement("create table extra.audit (id int)");
        both.statement("create view user_emails as select email from users");

        wide.dropAllViews();
        wide.dropAllTables();

        assertTrue(wide.getViews().isEmpty());
        assertTrue(wide.getTables().isEmpty());
    }

    @Test
    void dropAllOnAnEmptyDatabaseRunsNoStatement() {
        List<LoggedQuery> recorded = schema.pretend(s -> {
            s.dropAllViews();
            s.dropAllTables();
        });

        assertTrue(recorded.isEmpty(), recorded.stream().map(LoggedQuery::sql).collect(Collectors.toList())
                .toString());
        schema.dropAllViews();
        schema.dropAllTables();
        assertTrue(schema.getTables().isEmpty());
    }

    @Test
    void freshDropsEverythingIncludingForeignTablesAndRerunsMigrations() {
        Migrator migrator = Migrator.builder(schema)
                .migrations(Collections.singletonList(PostgresMigratorTest.blog().get(0))).build();
        migrator.run();
        db.statement("create table extra_table (id int)");
        db.statement("create view user_names as select name from users");
        db.insert("insert into users (name, email) values ('ann', 'a@x')");

        assertEquals(1, migrator.fresh().size());

        assertEquals(Arrays.asList("migrations", "users"), schema.getTableListing());
        assertTrue(schema.getViews().isEmpty());
        assertEquals(0L, ((Number) db.scalar("select count(*) from users").orElseThrow()).longValue());
    }
}

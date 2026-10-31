package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteEnumRebuildTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("enum.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        schema.create("teams", t -> t.id());
        db.insert("insert into teams (id) values (1)");
    }

    private void createUsers() {
        schema.create("users", t -> {
            t.id();
            t.integer("team_id");
            t.enumColumn("status", Arrays.asList("active", "o'k", "a) check (1", "in (x)"));
        });
        db.insert("insert into users (team_id, status) values (1, 'o''k'), (1, 'a) check (1')");
    }

    private long count(String table) {
        return ((Number) db.scalar("select count(*) from " + table).get()).longValue();
    }

    @Test
    void anEnumSurvivesARebuildWithItsCheck() {
        createUsers();

        schema.table("users", t -> t.foreign("team_id").references("id").on("teams"));

        assertEquals(2, count("users"));
        assertEquals(1, schema.foreignKeys("users").size());
        db.insert("insert into users (team_id, status) values (1, 'in (x)')");
        assertThrows(QueryException.class, () -> db.insert("insert into users (team_id, status) values (1, 'other')"));
    }

    @Test
    void aRenamedEnumKeepsItsCheckOnOldSqlite() {
        createUsers();
        Schema old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));

        old.table("users", t -> t.renameColumn("status", "state"));

        assertEquals(2, count("users"));
        assertTrue(old.hasColumn("users", "state"));
        assertThrows(QueryException.class, () -> db.insert("insert into users (team_id, state) values (1, 'other')"));
        db.insert("insert into users (team_id, state) values (1, 'active')");
    }

    @Test
    void anEnumAddedWhileARebuildIsPendingIsWrittenWithItsCheck() {
        createUsers();

        schema.table("users", t -> {
            t.enumColumn("tier", Arrays.asList("free", "paid")).defaultValue("free");
            t.foreign("team_id").references("id").on("teams");
        });

        db.insert("insert into users (team_id, status) values (1, 'active')");
        assertThrows(QueryException.class,
                () -> db.insert("insert into users (team_id, status, tier) values (1, 'active', 'gold')"));
    }

    @Test
    void aCheckOnAnotherColumnIsRefused() {
        db.statement("create table odd (a varchar check (\"b\" in ('x')), b varchar)");

        assertThrows(UnsupportedOperationException.class, () -> schema.table("odd", t -> t.primary("a")));
    }

    @Test
    void aTableLevelCheckIsRefusedEvenInEnumShape() {
        db.statement("create table odd (a varchar, check (\"a\" in ('x')))");

        assertThrows(UnsupportedOperationException.class, () -> schema.table("odd", t -> t.primary("a")));
    }

    @Test
    void theOwnerMatchesWithoutLetterCase() {
        db.statement("create table odd (Status varchar check (\"status\" in ('x', 'y')))");
        db.insert("insert into odd values ('x')");

        schema.table("odd", t -> t.primary("Status"));

        assertEquals(1, count("odd"));
        assertThrows(QueryException.class, () -> db.insert("insert into odd values ('z')"));
    }

    @Test
    void anEnumCheckWrittenDifferentlyIsRefused() {
        db.statement("create table odd (s varchar check (\"s\" in ('x','y')))");

        assertThrows(UnsupportedOperationException.class, () -> schema.table("odd", t -> t.primary("s")));
    }

    @Test
    void wordsInsideLiteralsDoNotTripTheRefusals() {
        db.statement("create table odd (s varchar default 'check (x) collate as generated', n integer)");
        db.insert("insert into odd (n) values (1)");

        schema.table("odd", t -> t.primary("n"));

        assertEquals("check (x) collate as generated", db.scalar("select s from odd").get());
    }

    @Test
    void collationGeneratedColumnsAndWithoutRowidAreStillRefused() {
        db.statement("create table c1 (s text collate nocase)");
        db.statement("create table c2 (a integer, b integer as (a * 2))");
        db.statement("create table c3 (a integer primary key) without rowid");

        assertThrows(UnsupportedOperationException.class, () -> schema.table("c1", t -> t.primary("s")));
        assertThrows(UnsupportedOperationException.class, () -> schema.table("c2", t -> t.primary("a")));
        assertThrows(UnsupportedOperationException.class, () -> schema.table("c3", t -> t.dropPrimary()));
    }

    @Test
    void tokensHandleQuotesCommentsAndBrackets() {
        java.util.List<SQLiteSqlTokens.Token> tokens = SQLiteSqlTokens.tokenize(
                "create table [a b] (`c` int -- note\n, 'it''s' /* x */ \"q\"\"r\")");

        assertEquals("a b", tokens.get(2).text);
        assertEquals("c", tokens.get(4).text);
        assertEquals("it's", tokens.get(7).text);
        assertEquals("q\"r", tokens.get(8).text);
        assertThrows(IllegalArgumentException.class, () -> SQLiteSqlTokens.tokenize("create table 'open"));
        assertThrows(IllegalArgumentException.class,
                () -> SQLiteSqlTokens.parts(SQLiteSqlTokens.tokenize("create table t")));
    }

    @Test
    void aYearDefaultAndACastDefaultDoNotLookGenerated() {
        schema.create("events", t -> {
            t.id();
            t.year("season").useCurrent();
            t.integer("team_id");
        });
        db.statement("create table casts (n integer default (cast('1' as integer)), m integer)");

        schema.table("events", t -> t.foreign("team_id").references("id").on("teams"));
        schema.table("casts", t -> t.primary("m"));

        assertEquals(1, schema.foreignKeys("events").size());
    }

    @Test
    void namedChecksAndStrictTablesAreRefused() {
        db.statement("create table named (s varchar constraint s_values check (\"s\" in ('x')))");
        db.statement("create table strict_t (a integer, b text) strict");

        assertThrows(UnsupportedOperationException.class, () -> schema.table("named", t -> t.primary("s")));
        assertThrows(UnsupportedOperationException.class, () -> schema.table("strict_t", t -> t.primary("a")));
    }
}

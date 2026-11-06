package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteQueryGrammarTest {

    private static final String GLOB = "\"name\" glob replace(replace(replace(replace(replace(?, '[', '[[]'), '*', '[*]'), '?', '[?]'), "
            + "'%', '*'), '_', '?')";

    @TempDir
    Path folder;

    private QueryFactory db;
    private QueryFactory prefixed;

    @BeforeEach
    void openFactories() {
        db = SQLiteQuery.of(connect(""));
        prefixed = SQLiteQuery.of(connect("app_"));
    }

    private Connection connect(String prefix) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("golden" + prefix + ".db"));
        return Connection.builder().name("sqlite").dataSource(dataSource).tablePrefix(prefix).build();
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    void theSQLiteOperatorsAreAcceptedAndWrittenLowerCase() {
        assertEquals(List.of("=", "<", ">", "<=", ">=", "<>", "!=", "like", "not like", "ilike", "&", "|", "<<", ">>"),
                new ArrayList<>(new SQLiteQueryGrammar("").getOperators()));
        assertEquals("select * from \"t\" where \"flags\" & ? and \"name\" like ? and \"n\" >> ?",
                db.table("t").where("flags", "&", 4).where("name", "LIKE", "a%").where("n", ">>", 1).toSql());
    }

    @Test
    void anUnknownOperatorIsTheValue() {
        Builder query = db.table("t").where("name", "glob");

        assertEquals("select * from \"t\" where \"name\" = ?", query.toSql());
        assertEquals(List.of("glob"), query.getBindings());
        Builder injected = db.table("t").where("a", "= 1 or 1 = 1 --");
        assertEquals("select * from \"t\" where \"a\" = ?", injected.toSql());
        assertEquals(List.of("= 1 or 1 = 1 --"), injected.getBindings());
    }

    @Test
    void caseInsensitiveLikeIsPlainLike() {
        assertEquals("select * from \"users\" where \"name\" like ? or \"name\" not like ?",
                db.table("users").whereLike("name", "a%").orWhereNotLike("name", "b%").toSql());
    }

    @Test
    void caseSensitiveLikeUsesGlobWithTheLikeWildcardsTranslated() {
        assertEquals("select * from \"users\" where " + GLOB,
                db.table("users").whereLike("name", "a%", true).toSql());
        assertEquals("select * from \"users\" where \"name\" not glob replace(replace(replace(replace(replace(?, '[', '[[]'), '*', '[*]'), "
                + "'?', '[?]'), '%', '*'), '_', '?')", db.table("users").whereNotLike("name", "a%", true).toSql());
    }

    @Test
    void nullSafeEqualsUsesIs() {
        assertEquals("select * from \"t\" where \"a\" is ? or \"b\" is ?",
                db.table("t").whereNullSafeEquals("a", 1).orWhereNullSafeEquals("b", null).toSql());
    }

    @Test
    void dateWheresUseStrftimeOnText() {
        assertEquals("select * from \"t\" where strftime('%Y-%m-%d', \"d\") = cast(? as text)"
                + " and strftime('%H:%M:%S', \"d\") >= cast(? as text)"
                + " and strftime('%d', \"d\") = cast(? as text)"
                + " and strftime('%m', \"d\") = cast(? as text)"
                + " and strftime('%Y', \"d\") < cast(? as text)",
                db.table("t").whereDate("d", LocalDate.of(2026, 1, 2)).whereTime("d", ">=", "10:00:00")
                        .whereDay("d", 2).whereMonth("d", 1).whereYear("d", "<", 2027).toSql());
    }

    @Test
    void anUpdateWithALimitGoesThroughRowid() {
        Builder query = db.table("users").where("id", ">", 1).orderBy("id").limit(2);

        String sql = query.getGrammar().compileUpdate(query, row("name", "x"));

        assertEquals("update \"users\" set \"name\" = ? where \"rowid\" in (select \"users\".\"rowid\" from \"users\""
                + " where \"id\" > ? order by \"id\" asc limit 2)", sql);
        assertEquals("select * from \"users\" where \"id\" > ? order by \"id\" asc limit 2", query.toSql(),
                "compiling must not change the caller's builder");
    }

    @Test
    void anUpdateWithAnAliasAndPrefixSelectsTheAliasedRowid() {
        Builder query = prefixed.table("users as u").where("u.id", 1).limit(1);

        assertEquals("update \"app_users\" as \"app_u\" set \"name\" = ? where \"rowid\" in (select \"app_u\".\"rowid\""
                + " from \"app_users\" as \"app_u\" where \"app_u\".\"id\" = ? limit 1)",
                query.getGrammar().compileUpdate(query, row("name", "x")));
    }

    @Test
    void anUpdateWithoutALimitIsPlain() {
        Builder query = db.table("users").where("id", 1).orderBy("id");

        assertEquals("update \"users\" set \"name\" = ? where \"id\" = ?",
                query.getGrammar().compileUpdate(query, row("name", "x")));
    }

    @Test
    void aDeleteWithALimitGoesThroughRowid() {
        Builder query = db.table("users").where("active", false).orderByDesc("id").limit(3);

        assertEquals("delete from \"users\" where \"rowid\" in (select \"users\".\"rowid\" from \"users\""
                + " where \"active\" = ? order by \"id\" desc limit 3)", query.getGrammar().compileDelete(query));
        assertEquals("select * from \"users\" where \"active\" = ? order by \"id\" desc limit 3", query.toSql(),
                "compiling must not change the caller's builder");
        assertEquals("delete from \"users\" where \"id\" = ?",
                db.table("users").where("id", 1).getGrammar().compileDelete(db.table("users").where("id", 1)));
    }

    @Test
    void truncateDeletesTheRowsAndResetsTheSequence() {
        Builder query = prefixed.table("users");

        Map<String, List<Object>> statements = query.getGrammar().compileTruncate(query);

        assertEquals(List.of("delete from sqlite_sequence where name = ?", "delete from \"app_users\""),
                new ArrayList<>(statements.keySet()));
        assertEquals(List.of("app_users"), statements.get("delete from sqlite_sequence where name = ?"));
        assertEquals(List.of(), statements.get("delete from \"app_users\""));
    }

    @Test
    void truncateOnASchemaUsesThatSchemasSequence() {
        Builder query = db.table("aux.users");

        Map<String, List<Object>> statements = query.getGrammar().compileTruncate(query);

        assertEquals(List.of("delete from \"aux\".sqlite_sequence where name = ?", "delete from \"aux\".\"users\""),
                new ArrayList<>(statements.keySet()));
        assertEquals(List.of("users"), statements.get("delete from \"aux\".sqlite_sequence where name = ?"));
    }

    @Test
    void instantsAreBoundAsEpochMicroseconds() {
        Instant moment = Instant.parse("2026-11-01T10:15:30.123456Z");

        List<Object> prepared = new SQLiteQueryGrammar("").prepareBindings(Arrays.asList(moment, null, "x", 1));

        assertEquals(Arrays.asList(1_793_528_130_123_456L, null, "x", 1), prepared);
    }

    @Test
    void whereInstantBetweenBindsMicroseconds() {
        Builder query = db.table("events").whereInstantBetween("at", Instant.EPOCH, Instant.ofEpochSecond(1));

        assertEquals("select * from \"events\" where (\"at\" >= ? and \"at\" < ?)", query.toSql());
        assertEquals("select * from \"events\" where (\"at\" >= 0 and \"at\" < 1000000)", query.toRawSql());
    }

    @Test
    void rawSqlWritesBooleansAsNumbersAndEscapesQuotes() {
        Builder query = db.table("t").where("active", true).where("deleted", false).where("name", "' or 1=1 --");

        assertEquals("select * from \"t\" where \"active\" = 1 and \"deleted\" = 0 and \"name\" = ''' or 1=1 --'",
                query.toRawSql());
    }

    @Test
    void rawSqlKeepsTheGlobLiteralsAndABackslashIsOrdinary() {
        Builder query = db.table("users").whereLike("name", "a\\", true).where("id", 1);

        assertEquals("select * from \"users\" where \"name\" glob replace(replace(replace(replace(replace('a\\', '[', '[[]'), '*', '[*]'), "
                + "'?', '[?]'), '%', '*'), '_', '?') and \"id\" = 1", query.toRawSql());
    }

    @Test
    void injectedNamesStayInsideQuotes() {
        assertEquals("select * from \"t\" where strftime('%Y', \"d\"\" ; drop table t; --\") = cast(? as text)",
                db.table("t").whereYear("d\" ; drop table t; --", 2026).toSql());
        Builder query = db.table("t\"x").where("a", 1).limit(1);
        assertEquals("delete from \"t\"\"x\" where \"rowid\" in (select \"t\"\"x\".\"rowid\" from \"t\"\"x\""
                + " where \"a\" = ? limit 1)", query.getGrammar().compileDelete(query));
    }

    @Test
    void aBackslashDoesNotEscapeAQuote() {
        assertFalse(new SQLiteQueryGrammar("") {
            boolean exposed() {
                return isBackslashEscape();
            }
        }.exposed());
        assertTrue(new SQLiteQueryGrammar("app_").getTablePrefix().equals("app_"));
    }

    // ---- unions, insert using, update and delete with joins ----

    @Test
    void eachUnionPartIsWrappedAsASelectFromASubquery() {
        Builder query = db.table("a").where("x", 1).union(db.table("b").where("y", 2)).unionAll(db.table("c"));

        assertEquals("select * from (select * from \"a\" where \"x\" = ?) union select * from (select * from \"b\" where \"y\" = ?)"
                + " union all select * from (select * from \"c\")", query.toSql());
        assertEquals(List.of(1, 2), query.getBindings());
    }

    @Test
    void orderAndLimitAfterAUnionApplyToTheWholeUnion() {
        Builder query = db.table("a").select("n").union(db.table("b").select("n")).orderBy("n").limit(5).offset(1);

        assertEquals("select * from (select \"n\" from \"a\") union select * from (select \"n\" from \"b\")"
                + " order by \"n\" asc limit 5 offset 1", query.toSql());
    }

    @Test
    void unionPartsKeepTheirOwnOrderAndLimitInsideTheWrapper() {
        Builder query = db.table("a").orderBy("n").limit(1).union(db.table("b").orderByDesc("n").limit(2));

        assertEquals("select * from (select * from \"a\" order by \"n\" asc limit 1) union"
                + " select * from (select * from \"b\" order by \"n\" desc limit 2)", query.toSql());
    }

    @Test
    void insertOrIgnoreUsingWritesInsertOrIgnore() {
        Builder query = db.table("users");

        assertEquals("insert or ignore into \"users\" (\"name\") select \"name\" from \"guests\"",
                query.getGrammar().compileInsertOrIgnoreUsing(query, List.of("name"), "select \"name\" from \"guests\""));
        assertEquals("insert or ignore into \"users\" select * from \"guests\"",
                query.getGrammar().compileInsertOrIgnoreUsing(query, List.of(), "select * from \"guests\""));
    }

    @Test
    void anUpdateWithAJoinGoesThroughRowid() {
        Builder query = db.table("users").join("posts", "users.id", "=", "posts.user_id").where("posts.v", 1);

        String sql = query.getGrammar().compileUpdate(query, row("name", "x"));

        assertEquals("update \"users\" set \"name\" = ? where \"rowid\" in (select \"users\".\"rowid\" from \"users\""
                + " inner join \"posts\" on \"users\".\"id\" = \"posts\".\"user_id\" where \"posts\".\"v\" = ?)", sql);
        assertEquals("select * from \"users\" inner join \"posts\" on \"users\".\"id\" = \"posts\".\"user_id\""
                + " where \"posts\".\"v\" = ?", query.toSql(), "compiling must not change the caller's builder");
    }

    @Test
    void anUpdateWithAJoinAndAnAliasSelectsTheAliasRowid() {
        Builder query = db.table("users as u").leftJoin("posts as p", "u.id", "=", "p.user_id").whereNull("p.id");

        assertEquals("update \"users\" as \"u\" set \"name\" = ? where \"rowid\" in (select \"u\".\"rowid\""
                + " from \"users\" as \"u\" left join \"posts\" as \"p\" on \"u\".\"id\" = \"p\".\"user_id\""
                + " where \"p\".\"id\" is null)", query.getGrammar().compileUpdate(query, row("name", "x")));
    }

    @Test
    void aDeleteWithAJoinGoesThroughRowid() {
        Builder query = db.table("users").join("posts", j -> j.on("users.id", "=", "posts.user_id").where("posts.v", 2));

        assertEquals("delete from \"users\" where \"rowid\" in (select \"users\".\"rowid\" from \"users\""
                + " inner join \"posts\" on \"users\".\"id\" = \"posts\".\"user_id\" and \"posts\".\"v\" = ?)",
                query.getGrammar().compileDelete(query));
        assertEquals("select * from \"users\" inner join \"posts\" on \"users\".\"id\" = \"posts\".\"user_id\""
                + " and \"posts\".\"v\" = ?", query.toSql(), "compiling must not change the caller's builder");
    }

    @Test
    void joinedUpdateBindingsLineUpWithTheMarks() {
        Builder query = db.table("users").join("posts", j -> j.on("users.id", "=", "posts.user_id").where("posts.v", 2))
                .where("users.id", ">", 3);
        Map<String, Object> values = row("name", "x", "votes", db.table("posts").selectRaw("count(*)").where("w", 1));

        String sql = query.getGrammar().compileUpdate(query, values);

        assertEquals("update \"users\" set \"name\" = ?, \"votes\" = (select count(*) from \"posts\" where \"w\" = ?)"
                + " where \"rowid\" in (select \"users\".\"rowid\" from \"users\" inner join \"posts\" on"
                + " \"users\".\"id\" = \"posts\".\"user_id\" and \"posts\".\"v\" = ? where \"users\".\"id\" > ?)", sql);
        assertEquals(List.of("x", 1, 2, 3),
                query.getGrammar().prepareBindingsForUpdate(query, values));
    }

    @Test
    void updateWithAJoinOrderAndLimitGoesThroughRowidAndBindsInMarkOrder() {
        Builder query = db.table("users")
                .join("posts", j -> j.on("users.id", "=", "posts.user_id").where("posts.v", 2))
                .where("users.id", ">", 3)
                .orderBy("users.id")
                .limit(2);
        Map<String, Object> values = row("name", "x");

        assertEquals("update \"users\" set \"name\" = ? where \"rowid\" in (select \"users\".\"rowid\" from \"users\""
                + " inner join \"posts\" on \"users\".\"id\" = \"posts\".\"user_id\" and \"posts\".\"v\" = ?"
                + " where \"users\".\"id\" > ? order by \"users\".\"id\" asc limit 2)",
                query.getGrammar().compileUpdate(query, values));
        assertEquals(List.of("x", 2, 3), query.getGrammar().prepareBindingsForUpdate(query, values));
    }

    @Test
    void eagerChunkSizes() {
        SQLiteQueryGrammar grammar = new SQLiteQueryGrammar("");

        assertEquals(900, grammar.eagerKeyChunkSize());
        assertEquals(20_000, grammar.inlineKeyChunkSize());
    }
}

package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Join golden SQL, ported from Laravel's DatabaseQueryBuilderTest testJoins* cases. */
class JoinClauseTest {

    @Test
    void basicJoinsCompileWithOnAndTheirType() {
        assertEquals("select * from \"users\" inner join \"contacts\" on \"users\".\"id\" = \"contacts\".\"id\" "
                        + "left join \"photos\" on \"users\".\"id\" = \"photos\".\"id\"",
                db().table("users").join("contacts", "users.id", "=", "contacts.id")
                        .leftJoin("photos", "users.id", "=", "photos.id").toSql());
        assertEquals("select * from \"users\" right join \"photos\" on \"users\".\"id\" = \"photos\".\"id\"",
                db().table("users").rightJoin("photos", "users.id", "=", "photos.id").toSql());
    }

    @Test
    void joinWhereBindsTheSecondValueIntoTheJoinBucket() {
        Builder query = db().table("users").leftJoinWhere("photos", "users.id", "=", "bar")
                .joinWhere("photos", "users.id", "=", "foo").rightJoinWhere("x", "x.a", ">", 3);
        assertEquals("select * from \"users\" left join \"photos\" on \"users\".\"id\" = ? "
                + "inner join \"photos\" on \"users\".\"id\" = ? right join \"x\" on \"x\".\"a\" > ?", query.toSql());
        assertEquals(Arrays.asList("bar", "foo", 3), query.getRawBindings().get(BindingType.JOIN));
        assertTrue(query.getRawBindings().get(BindingType.WHERE).isEmpty());
    }

    @Test
    void crossJoinsWithAndWithoutConditions() {
        assertEquals("select * from \"sizes\" cross join \"colors\"",
                db().table("sizes").crossJoin("colors").toSql());
        assertEquals("select * from \"tableB\" cross join \"tableA\" on \"tableA\".\"column1\" = \"tableB\".\"column1\"",
                db().table("tableB").crossJoin("tableA", "tableA.column1", "=", "tableB.column1").toSql());
        assertEquals("select * from \"sizes\" cross join (select * from \"colors\" where \"a\" = ?) as \"c\"",
                db().table("sizes").crossJoinSub(db().table("colors").where("a", 1), "c").toSql());
    }

    @Test
    void complexJoinWithOnOrOnAndWheres() {
        Builder query = db().table("users").join("contacts", j -> j.on("users.id", "=", "contacts.id")
                .orOn("users.name", "=", "contacts.name").where("contacts.a", "=", "foo").orWhere("contacts.b", "bar"));
        assertEquals("select * from \"users\" inner join \"contacts\" on \"users\".\"id\" = \"contacts\".\"id\" "
                + "or \"users\".\"name\" = \"contacts\".\"name\" and \"contacts\".\"a\" = ? or \"contacts\".\"b\" = ?",
                query.toSql());
        assertEquals(Arrays.asList("foo", "bar"), query.getBindings());
    }

    @Test
    void twoArgumentOnComparesWithEquals() {
        assertEquals("select * from \"users\" inner join \"contacts\" on \"users\".\"id\" = \"contacts\".\"id\"",
                db().table("users").join("contacts", j -> j.on("users.id", "contacts.id")).toSql());
    }

    @Test
    void joinWhereNullAndWhereInBecomeOnConditions() {
        Builder query = db().table("users").join("contacts", j -> j.on("users.id", "=", "contacts.id")
                .whereNull("contacts.deleted_at").whereIn("contacts.name", Arrays.asList(48, "baz", null)));
        assertEquals("select * from \"users\" inner join \"contacts\" on \"users\".\"id\" = \"contacts\".\"id\" "
                + "and \"contacts\".\"deleted_at\" is null and \"contacts\".\"name\" in (?, ?, ?)", query.toSql());
        assertEquals(Arrays.asList(48, "baz", null), query.getRawBindings().get(BindingType.JOIN));
    }

    @Test
    void joinWhereWithNullIsAnIsNullWithoutBinding() {
        Builder query = db().table("users").join("contacts", j -> j.on("users.id", "=", "contacts.id")
                .where("contacts.x", "=", (Object) null));
        assertEquals("select * from \"users\" inner join \"contacts\" on \"users\".\"id\" = \"contacts\".\"id\" "
                + "and \"contacts\".\"x\" is null", query.toSql());
        assertTrue(query.getBindings().isEmpty());
    }

    @Test
    void nestedOnGroupsUseParenthesesAndAnEmptyGroupIsSkipped() {
        Builder query = db().table("users").leftJoin("contacts", j -> j.on("users.id", "contacts.id")
                .where(g -> g.where("contacts.country", "=", "US").orWhere("contacts.is_partner", "=", 1))
                .on(g -> g.on("a.x", "=", "b.x").orOn("a.y", "=", "b.y"))
                .on(g -> { }));
        assertEquals("select * from \"users\" left join \"contacts\" on \"users\".\"id\" = \"contacts\".\"id\" "
                + "and (\"contacts\".\"country\" = ? or \"contacts\".\"is_partner\" = ?) "
                + "and (\"a\".\"x\" = \"b\".\"x\" or \"a\".\"y\" = \"b\".\"y\")", query.toSql());
        assertEquals(Arrays.asList("US", 1), query.getBindings());
    }

    @Test
    void orOnWithAGroup() {
        assertEquals("select * from \"a\" inner join \"b\" on \"a\".\"id\" = \"b\".\"id\" or (\"a\".\"x\" = \"b\".\"x\")",
                db().table("a").join("b", j -> j.on("a.id", "=", "b.id").orOn(g -> g.on("a.x", "=", "b.x"))).toSql());
    }

    @Test
    void joinSubFromABuilderAConsumerOrRawText() {
        assertEquals("select * from \"users\" inner join (select * from \"contacts\") as \"sub\" on \"users\".\"id\" = \"sub\".\"id\"",
                db().table("users").joinSub("select * from \"contacts\"", "sub", "users.id", "=", "sub.id").toSql());
        Builder query = db().table("users")
                .joinSub(db().table("contacts").where("name", "foo"), "sub1", "users.id", "=", "sub1.id")
                .leftJoinSub(q -> q.from("contacts").where("name", "bar"), "sub2",
                        j -> j.on("users.id", "=", "sub2.id").where("sub2.z", 3))
                .rightJoinSub(db().table("r"), "sub3", "users.id", "=", "sub3.id");
        assertEquals("select * from \"users\" inner join (select * from \"contacts\" where \"name\" = ?) as \"sub1\" "
                + "on \"users\".\"id\" = \"sub1\".\"id\" left join (select * from \"contacts\" where \"name\" = ?) as \"sub2\" "
                + "on \"users\".\"id\" = \"sub2\".\"id\" and \"sub2\".\"z\" = ? "
                + "right join (select * from \"r\") as \"sub3\" on \"users\".\"id\" = \"sub3\".\"id\"", query.toSql());
        assertEquals(Arrays.asList("foo", "bar", 3), query.getRawBindings().get(BindingType.JOIN));
    }

    @Test
    void joinSubAliasGetsThePrefixAndIsQuotedSafely() {
        assertEquals("select * from \"p_users\" inner join (select * from \"p_c\") as \"p_s\"\"x\" "
                + "on \"p_users\".\"id\" = \"p_s\"\"x\".\"id\"",
                db("p_").table("users").joinSub(db("p_").table("c"), "s\"x", "users.id", "=", "s\"x.id").toSql());
    }

    @Test
    void aliasedJoinTable() {
        assertEquals("select * from \"users\" inner join \"contacts\" as \"c\" on \"users\".\"id\" = \"c\".\"id\"",
                db().table("users").join("contacts as c", "users.id", "=", "c.id").toSql());
    }

    @Test
    void joinClauseKeepsItsTypeAndTableThroughCloneAndNewQuery() {
        AtomicReference<JoinClause> seen = new AtomicReference<>();
        db().table("a").leftJoin("b", j -> {
            j.on("a.id", "=", "b.id");
            seen.set(j);
        });
        JoinClause join = seen.get();
        JoinClause copy = join.clone();
        assertNotSame(join, copy);
        assertEquals("left", copy.getType());
        assertEquals("b", copy.getTable());
        assertEquals(join.getWheres().size(), copy.getWheres().size());
        JoinClause fresh = join.newQuery();
        assertEquals("left", fresh.getType());
        assertTrue(fresh.getWheres().isEmpty());
    }

    @Test
    void changingAJoinClauseAfterAttachDoesNotChangeTheQuery() {
        AtomicReference<JoinClause> seen = new AtomicReference<>();
        Builder query = db().table("a").join("b", j -> seen.set(j.on("a.id", "=", "b.id")));
        String sql = query.toSql();
        seen.get().where("b.x", 1);
        assertEquals(sql, query.toSql());
        assertTrue(query.getBindings().isEmpty());
    }

    @Test
    void getJoinsReturnsCopies() {
        Builder query = db().table("a").join("b", "a.id", "=", "b.id");
        List<JoinClause> joins = query.getJoins();
        assertEquals(1, joins.size());
        joins.get(0).where("b.x", 1);
        assertEquals("select * from \"a\" inner join \"b\" on \"a\".\"id\" = \"b\".\"id\"", query.toSql());
    }

    @Test
    void nullArgumentsAreRefused() {
        assertThrows(NullPointerException.class, () -> db().table("a").join(null, "a", "=", "b"));
        assertThrows(NullPointerException.class, () -> db().table("a").join("b", (java.util.function.Consumer<JoinClause>) null));
        assertThrows(NullPointerException.class, () -> db().table("a").joinSub((Builder) null, "s", "a", "=", "b"));
        assertThrows(NullPointerException.class, () -> db().table("a").joinSub(db().table("b"), null, "a", "=", "b"));
    }
}

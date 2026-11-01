package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.RecordingConnection.row;
import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Union golden SQL, ported from Laravel's DatabaseQueryBuilderTest testUnion* cases. */
class UnionTest {

    @Test
    void unionsWrapEachPartAndBindInOrder() {
        Builder query = db().table("users").where("id", "=", 1)
                .union(db().table("users").where("id", "=", 2));
        assertEquals("(select * from \"users\" where \"id\" = ?) union (select * from \"users\" where \"id\" = ?)",
                query.toSql());
        assertEquals(Arrays.asList(1, 2), query.getBindings());
        assertEquals(Collections.singletonList(2), query.getRawBindings().get(BindingType.UNION));
    }

    @Test
    void unionAllAndConsumerSources() {
        assertEquals("(select * from \"a\") union all (select * from \"b\") union (select * from \"c\")",
                db().table("a").unionAll(q -> q.from("b")).union(q -> q.from("c")).toSql());
        assertEquals("(select * from \"a\") union all (select * from \"b\")",
                db().table("a").unionAll(db().table("b")).toSql());
    }

    @Test
    void orderLimitAndOffsetAfterAUnionApplyToTheWholeUnion() {
        Builder query = db().table("users").orderBy("a").limit(1).union(db().table("dogs"))
                .orderBy("id", "desc").orderByRaw("field(x, ?)", Collections.singletonList(7)).skip(10).take(5);
        assertEquals("(select * from \"users\" order by \"a\" asc limit 1) union (select * from \"dogs\") "
                + "order by \"id\" desc, field(x, ?) limit 5 offset 10", query.toSql());
        assertEquals(Collections.singletonList(7), query.getRawBindings().get(BindingType.UNION_ORDER));
        assertEquals(5, query.getUnionLimit().getAsInt());
        assertEquals(10, query.getUnionOffset().getAsInt());
        assertEquals(1, query.getLimit().getAsInt());
        assertEquals(2, query.getUnionOrders().size());
    }

    @Test
    void reorderClearsUnionOrdersToo() {
        Builder query = db().table("a").union(db().table("b")).orderBy("x").reorder();
        assertEquals("(select * from \"a\") union (select * from \"b\")", query.toSql());
        assertTrue(query.getUnionOrders().isEmpty());
    }

    @Test
    void unionOrderBySubqueryBindsIntoUnionOrder() {
        Builder query = db().table("a").union(db().table("b")).orderBy(db().table("c").select("d").where("e", 1), "asc");
        assertEquals("(select * from \"a\") union (select * from \"b\") order by (select \"d\" from \"c\" where \"e\" = ?) asc",
                query.toSql());
        assertEquals(Collections.singletonList(1), query.getRawBindings().get(BindingType.UNION_ORDER));
    }

    @Test
    void existsWrapsTheUnion() {
        assertEquals("select exists((select * from \"a\") union (select * from \"b\")) as \"exists\"",
                db().query().getGrammar().compileExists(db().table("a").union(db().table("b"))));
    }

    @Test
    void countOverAUnionUsesTheTempTableAndLeavesTheBuilderAlone() {
        RecordingConnection recording = new RecordingConnection();
        String sql = "select count(*) as \"aggregate\" from ((select * from \"posts\" where \"a\" = ?) "
                + "union (select * from \"videos\")) as \"temp_table\"";
        recording.returns(sql, Collections.singletonList(row("aggregate", 3L)));
        Builder query = recording.db().table("posts").where("a", 1).union(recording.db().table("videos"));
        String before = query.toSql();
        assertEquals(3L, query.count());
        assertEquals(sql, recording.last().sql());
        assertEquals(Collections.singletonList(1), recording.last().bindings());
        assertEquals(before, query.toSql());
    }

    @Test
    void unionsWithOrdersKeepUnionOrderInTheAggregate() {
        RecordingConnection recording = new RecordingConnection();
        Builder query = recording.db().table("p").orderBy("x").union(recording.db().table("v")).orderBy("y").limit(2);
        query.count();
        assertEquals("select count(*) as \"aggregate\" from ((select * from \"p\") "
                + "union (select * from \"v\") order by \"y\" asc limit 2) as \"temp_table\"", recording.last().sql());
    }

    @Test
    void getUnionsReturnsCopies() {
        Builder query = db().table("a").union(db().table("b"));
        List<Union> unions = query.getUnions();
        assertEquals(1, unions.size());
        unions.get(0).query().where("x", 1);
        assertEquals("(select * from \"a\") union (select * from \"b\")", query.toSql());
        assertTrue(!unions.get(0).isAll());
    }
}

package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One query with a subquery in every slot: the bindings must come out in the order of the {@code ?} marks. */
class BindingOrderTest {

    @Test
    void bindingsFollowTheMarksInEverySlot() {
        Builder query = db().query()
                .select("a")
                .selectSub(db().table("s").selectRaw("count(*)").where("s1", 1), "c")
                .fromSub(db().table("f").where("f1", 2), "x")
                .joinSub(db().table("j").where("j1", 3), "y", "x.id", "=", "y.id")
                .join("k", j -> j.on("k.id", "=", "x.id").where("k.v", 4))
                .whereIn("w", db().table("w").select("id").where("w1", 5))
                .where("z", 6)
                .groupBy("g")
                .havingRaw("sum(h) > ?", Collections.singletonList(7))
                .orderBy(db().table("o").select("v").where("o1", 8).limit(1), "desc")
                .union(db().table("u").select("a").where("u1", 9))
                .orderBy(db().table("q").select("v").where("q1", 10), "asc");

        assertEquals("(select \"a\", (select count(*) from \"s\" where \"s1\" = ?) as \"c\" "
                + "from (select * from \"f\" where \"f1\" = ?) as \"x\" "
                + "inner join (select * from \"j\" where \"j1\" = ?) as \"y\" on \"x\".\"id\" = \"y\".\"id\" "
                + "inner join \"k\" on \"k\".\"id\" = \"x\".\"id\" and \"k\".\"v\" = ? "
                + "where \"w\" in (select \"id\" from \"w\" where \"w1\" = ?) and \"z\" = ? "
                + "group by \"g\" having sum(h) > ? "
                + "order by (select \"v\" from \"o\" where \"o1\" = ? limit 1) desc) "
                + "union (select \"a\" from \"u\" where \"u1\" = ?) "
                + "order by (select \"v\" from \"q\" where \"q1\" = ?) asc", query.toSql());
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), query.getBindings());
        // Each subquery's own value sits where its slot is, so the raw SQL reads 1 to 10 left to right.
        assertEquals("(select \"a\", (select count(*) from \"s\" where \"s1\" = 1) as \"c\" "
                + "from (select * from \"f\" where \"f1\" = 2) as \"x\" "
                + "inner join (select * from \"j\" where \"j1\" = 3) as \"y\" on \"x\".\"id\" = \"y\".\"id\" "
                + "inner join \"k\" on \"k\".\"id\" = \"x\".\"id\" and \"k\".\"v\" = 4 "
                + "where \"w\" in (select \"id\" from \"w\" where \"w1\" = 5) and \"z\" = 6 "
                + "group by \"g\" having sum(h) > 7 "
                + "order by (select \"v\" from \"o\" where \"o1\" = 8 limit 1) desc) "
                + "union (select \"a\" from \"u\" where \"u1\" = 9) "
                + "order by (select \"v\" from \"q\" where \"q1\" = 10) asc", query.toRawSql());
    }

    @Test
    void anUpdateBindsOnlyWhatItWrites() {
        Map<String, Object> values = Collections.singletonMap("a", 1);
        for (Builder query : writeQueries()) {
            assertEquals(marks(query.getGrammar().compileUpdate(query, values)),
                    query.getGrammar().prepareBindingsForUpdate(query, values).size(), query.toSql());
        }
    }

    @Test
    void aDeleteBindsOnlyWhatItWrites() {
        for (Builder query : writeQueries()) {
            assertEquals(marks(query.getGrammar().compileDelete(query)),
                    query.getGrammar().prepareBindingsForDelete(query).size(), query.toSql());
        }
    }

    private static Builder[] writeQueries() {
        return new Builder[] {
            db().table("t").where("w", 2).orderByRaw("x = ?", Collections.singletonList(3)),
            db().table("t").where("w", 2).groupByRaw("g + ?", Collections.singletonList(3))
                    .havingRaw("sum(h) > ?", Collections.singletonList(4)),
            db().table("t").where("w", 2).union(db().table("u").where("u1", 3)),
        };
    }

    private static int marks(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }
}

package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Test;

/** Joins, subqueries and unions run against a real PostgreSQL server. */
class PostgresJoinsUnionsIntegrationTest {

    private static QueryFactory shop() {
        Connection connection = PostgresServer.newDatabase();
        Schema schema = PostgresSchema.of(connection);
        schema.create("people", t -> {
            t.id();
            t.string("name").unique();
            t.integer("age");
        });
        schema.create("orders", t -> {
            t.id();
            t.integer("person_id");
            t.integer("total");
        });
        QueryFactory db = PostgresQuery.of(connection);
        db.table("people").insert(List.of(
                Map.of("name", "Ann", "age", 30),
                Map.of("name", "Bob", "age", 40),
                Map.of("name", "Cid", "age", 50)));
        db.table("orders").insert(List.of(
                Map.of("person_id", 1, "total", 10),
                Map.of("person_id", 1, "total", 20),
                Map.of("person_id", 2, "total", 5),
                Map.of("person_id", 9, "total", 7)));
        return db;
    }

    private static List<String> column(List<Map<String, Object>> rows, String name) {
        return rows.stream().map(r -> String.valueOf(r.get(name))).collect(Collectors.toList());
    }

    @Test
    void joinsOfEveryKind() {
        QueryFactory db = shop();

        assertEquals(List.of("Ann:10", "Ann:20", "Bob:5"), column(db.table("people")
                .join("orders", "people.id", "=", "orders.person_id")
                .selectRaw("name || ':' || total as v").orderBy("orders.id").get(), "v"));
        assertEquals(List.of("Ann", "Ann", "Bob", "Cid"), column(db.table("people")
                .leftJoin("orders", "people.id", "=", "orders.person_id")
                .select("name").orderBy("people.id").orderBy("orders.id").get(), "name"));
        assertEquals(List.of("10", "20", "5", "7"), column(db.table("people")
                .rightJoin("orders", "people.id", "=", "orders.person_id")
                .select("total").orderBy("orders.id").get(), "total"));
        assertEquals(12L, db.table("people").crossJoin("orders").count());
    }

    @Test
    void nestedOnWithAWhereFiltersTheJoin() {
        QueryFactory db = shop();

        List<Map<String, Object>> rows = db.table("people")
                .join("orders", j -> j.on("people.id", "=", "orders.person_id").where("orders.total", ">", 15))
                .select("name", "total").get();

        assertEquals(List.of("Ann"), column(rows, "name"));
        assertEquals(List.of("20"), column(rows, "total"));
    }

    @Test
    void subqueriesInFromJoinSelectAndWhere() {
        QueryFactory db = shop();
        Builder sums = db.table("orders").selectRaw("person_id, sum(total) as spent").groupBy("person_id");

        assertEquals(List.of("Ann:30", "Bob:5"), column(db.table("people").joinSub(sums, "s", "people.id", "=",
                "s.person_id").selectRaw("name || ':' || spent as v").orderBy("people.id").get(), "v"));
        assertEquals(3L, db.query().fromSub(sums, "s").count());
        assertEquals(List.of("2", "1", "0"), column(db.table("people").select("name")
                .selectSub(db.table("orders").selectRaw("count(*)").whereColumn("orders.person_id", "people.id"),
                        "n")
                .orderBy("people.id").get(), "n"));
        assertEquals(List.of("Ann", "Bob"), column(db.table("people")
                .whereIn("id", db.table("orders").select("person_id")).orderBy("id").get(), "name"));
        assertEquals(List.of("Ann"), column(db.table("people").whereExists(db.table("orders")
                .whereColumn("orders.person_id", "people.id").where("total", ">", 15)).get(), "name"));
    }

    @Test
    void unionsWithOrderLimitAndAggregates() {
        QueryFactory db = shop();
        Builder union = db.table("people").select("name").where("age", "<", 45)
                .union(db.table("people").select("name").where("age", ">", 35));

        assertEquals(List.of("Cid", "Bob"), column(union.clone().orderBy("name", "desc").limit(2).get(), "name"));
        assertEquals(3L, union.count());
        assertTrue(union.exists());
        Builder all = db.table("people").select("name").where("age", "<", 45)
                .unionAll(db.table("people").select("name").where("age", ">", 35));
        assertEquals(4L, all.count());
    }

    @Test
    void insertUsingAndInsertOrIgnoreUsingSkipDuplicates() {
        QueryFactory db = shop();

        assertEquals(1, db.table("people").insertUsing(Arrays.asList("name", "age"),
                db.query().selectRaw("'Dee', 70")));
        assertEquals(1, db.table("people").insertOrIgnoreUsing(Arrays.asList("name", "age"),
                db.table("people").selectRaw("name || '2', age").where("name", "Ann")
                        .unionAll(db.table("people").selectRaw("name, age").where("name", "Bob"))));

        assertEquals(List.of("Ann", "Bob", "Cid", "Dee", "Ann2"),
                column(db.table("people").orderBy("id").get(), "name"));
    }

    @Test
    void updateWithASubqueryValue() {
        QueryFactory db = shop();

        int changed = db.table("people").where("name", "Ann").update(Collections.singletonMap("age",
                db.table("orders").selectRaw("sum(total)").where("person_id", 1)));

        assertEquals(1, changed);
        assertEquals(30, ((Number) db.table("people").where("name", "Ann").value("age").orElseThrow()).intValue());
    }

    @Test
    void updateAndDeleteWithAJoinChangeOnlyMatchingRows() {
        QueryFactory db = shop();
        Builder big = db.table("people").join("orders", j -> j.on("people.id", "=", "orders.person_id")
                .where("orders.total", ">=", 10)).where("people.age", "<", 45);
        String before = big.toSql();

        assertEquals(1, big.update(Collections.singletonMap("name", "Big")));
        assertEquals(before, big.toSql());
        assertEquals(List.of("Big", "Bob", "Cid"), column(db.table("people").orderBy("id").get(), "name"));

        assertEquals(1, big.delete());
        assertEquals(List.of("Bob", "Cid"), column(db.table("people").orderBy("id").get(), "name"));
    }

    @Test
    void theBindingOrderScenarioRuns() {
        Connection connection = PostgresServer.newDatabase();
        Schema schema = PostgresSchema.of(connection);
        for (String table : Arrays.asList("s", "f", "j", "k", "w", "o", "u", "q")) {
            schema.create(table, t -> {
                for (String column : Arrays.asList("id", "a", "v", "s1", "f1", "j1", "w1", "o1", "u1", "q1", "z",
                        "g", "h", "w")) {
                    t.integer(column).nullable();
                }
            });
        }
        QueryFactory db = PostgresQuery.of(connection);
        Map<String, Object> f = Map.of("id", 1, "a", 100, "f1", 2, "z", 6, "g", 1, "h", 8, "w", 1);
        db.table("f").insert(f);
        db.table("s").insert(Map.of("s1", 1));
        db.table("j").insert(Map.of("id", 1, "j1", 3));
        db.table("k").insert(Map.of("id", 1, "v", 4));
        db.table("w").insert(Map.of("id", 1, "w1", 5));
        db.table("o").insert(Map.of("v", 1, "o1", 8));
        db.table("u").insert(Map.of("a", 200, "u1", 9));
        
        Builder query = db.query()
                .select("x.a")
                .selectSub(db.table("s").selectRaw("count(*)").where("s1", 1), "c")
                .fromSub(db.table("f").where("f1", 2), "x")
                .joinSub(db.table("j").where("j1", 3), "y", "x.id", "=", "y.id")
                .join("k", j -> j.on("k.id", "=", "x.id").where("k.v", 4))
                .whereIn("x.w", db.table("w").select("id").where("w1", 5))
                .where("x.z", 6)
                .groupBy("x.a")
                .havingRaw("sum(x.h) > ?", Collections.singletonList(7))
                .orderBy(db.table("o").select("v").where("o1", 8).limit(1), "desc")
                .union(db.table("u").selectRaw("a, 0").where("u1", 9))
                // PostgreSQL only orders a union by an output column, so slot 10 orders by name here.
                .orderBy("a", "asc");

        assertEquals(List.of("100", "200"), column(query.get(), "a"));
        assertEquals(2, db.connection().select(query.toRawSql()).size());
    }
}

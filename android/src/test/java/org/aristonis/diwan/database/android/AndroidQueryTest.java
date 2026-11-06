package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.values.Instants;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidQueryTest {

    private static final int[] RIGHT_JOIN_SQLITE = {3, 39};

    private SQLiteDatabase database;
    private Connection db;
    private QueryFactory query;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table items (id integer primary key autoincrement, name text, qty integer,"
                + " price integer, price_text text, at integer)");
        query = SQLiteQuery.of(db);
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    private void seed() {
        query.table("items").insert(List.of(
                Map.of("name", "a", "qty", 1),
                Map.of("name", "b", "qty", 2),
                Map.of("name", "c", "qty", 3)));
    }

    @Test
    public void insertManyThenReadsAndAggregates() {
        seed();

        assertEquals(3, query.table("items").count());
        assertEquals(6L, query.table("items").sum("qty").longValue());
        assertEquals(3L, ((Number) query.table("items").max("qty").orElseThrow()).longValue());
        assertEquals(2.0, query.table("items").avg("qty").orElseThrow().doubleValue(), 0.0);
        assertEquals(List.of("a", "b", "c"), query.table("items").orderBy("id").pluck("name"));
        assertEquals("b", query.table("items").where("qty", 2).value("name").orElseThrow());
        assertEquals("c", query.table("items").orderByDesc("qty").first().orElseThrow().get("name"));
        assertTrue(query.table("items").where("name", "a").exists());
        assertFalse(query.table("items").where("name", "z").exists());
    }

    @Test
    public void insertGetIdReturnsTheNewKeyOnTheAndroidSqliteVersion() {
        String version = db.getDriverInfo().databaseVersion();
        System.out.println("Robolectric SQLite version: " + version);
        seed();

        Object id = query.table("items").insertGetId(Map.of("name", "d", "qty", 4));

        assertEquals(4L, ((Number) id).longValue());
        assertEquals("d", query.table("items").find(id).orElseThrow().get("name"));
    }

    @Test
    public void updateIncrementAndDeleteHonourALimit() {
        seed();

        assertEquals(1, query.table("items").orderBy("id").limit(1).update(Map.of("name", "x")));
        assertEquals(1, query.table("items").orderByDesc("id").limit(1).increment("qty", 10));
        assertEquals(1, query.table("items").orderBy("id").limit(1).delete());

        assertEquals(List.of("b", "c"), query.table("items").orderBy("id").pluck("name"));
        assertEquals(13L, ((Number) query.table("items").where("name", "c").value("qty").orElseThrow()).longValue());
    }

    @Test
    public void truncateResetsIds() {
        seed();

        query.table("items").truncate();
        Object id = query.table("items").insertGetId(Map.of("name", "fresh"));

        assertEquals(1L, ((Number) id).longValue());
    }

    @Test
    public void instantRoundTrip() {
        Instant at = Instant.parse("2026-11-01T10:15:30.123456Z");

        query.table("items").insert(Map.of("name", "m", "at", at));
        Map<String, Object> row = query.table("items").first().orElseThrow();

        assertEquals(at, Instants.read(row.get("at")));
    }

    @Test
    public void whereInstantBetweenSelectsTheRange() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        query.table("items").insert(List.of(
                Map.of("name", "before", "at", base.minusSeconds(1)),
                Map.of("name", "inside", "at", base.plusSeconds(60)),
                Map.of("name", "after", "at", base.plusSeconds(3600))));

        List<Object> names = query.table("items")
                .whereInstantBetween("at", base, base.plusSeconds(120)).pluck("name");

        assertEquals(List.of("inside"), names);
    }

    @Test
    public void toRawSqlInlinesBindings() {
        String sql = query.table("items").where("name", "o'k").where("qty", ">", 2).toRawSql();

        assertEquals("select * from \"items\" where \"name\" = 'o''k' and \"qty\" > 2", sql);
    }

    private void seedShop() {
        db.statement("create table customers (id integer primary key, name text)");
        db.statement("create table orders (id integer primary key, customer_id integer, total integer)");
        query.table("customers").insert(List.of(
                Map.of("id", 1, "name", "ann"), Map.of("id", 2, "name", "bob"), Map.of("id", 3, "name", "cy")));
        query.table("orders").insert(List.of(
                Map.of("id", 1, "customer_id", 1, "total", 10),
                Map.of("id", 2, "customer_id", 1, "total", 20),
                Map.of("id", 3, "customer_id", 2, "total", 5)));
    }

    @Test
    public void innerAndLeftJoinsReturnTheMatchingRows() {
        seedShop();

        List<Object> inner = query.table("customers").join("orders", "customers.id", "=", "orders.customer_id")
                .orderBy("orders.id").pluck("customers.name");
        List<Object> withoutOrders = query.table("customers")
                .leftJoin("orders", "customers.id", "=", "orders.customer_id")
                .whereNull("orders.id").pluck("customers.name");

        assertEquals(List.of("ann", "ann", "bob"), inner);
        assertEquals(List.of("cy"), withoutOrders);
    }

    @Test
    public void rightJoinNeedsSqlite339AndOlderSqlitePassesItsErrorThrough() {
        seedShop();
        String version = db.getDriverInfo().databaseVersion();

        if (isAtLeast(version, RIGHT_JOIN_SQLITE)) {
            assertEquals(3, query.table("orders").rightJoin("customers", "customers.id", "=", "orders.customer_id")
                    .where("customers.id", "<", 3).count());
        } else {
            assertThrows(QueryException.class, () -> query.table("orders")
                    .rightJoin("customers", "customers.id", "=", "orders.customer_id").get());
        }
    }

    @Test
    public void whereExistsWithASubqueryFiltersTheOuterRows() {
        seedShop();

        List<Object> names = query.table("customers")
                .whereExists(q -> q.from("orders").whereColumn("orders.customer_id", "customers.id")
                        .where("total", ">", 10))
                .pluck("name");

        assertEquals(List.of("ann"), names);
    }

    @Test
    public void unionPartsAndTheUnionTakeTheirOwnOrderAndLimit() {
        seedShop();

        List<Object> names = query.table("customers").select("name").orderBy("id").limit(1)
                .union(query.table("customers").select("name").orderByDesc("id").limit(1))
                .union(query.table("customers").select("name").where("id", 2))
                .orderByDesc("name").limit(2).pluck("name");

        assertEquals(List.of("cy", "bob"), names);
    }

    @Test
    public void countOverAUnionCountsTheCombinedRows() {
        seedShop();

        assertEquals(6, query.table("customers").unionAll(query.table("customers")).count());
        assertEquals(3, query.table("customers").union(query.table("customers")).count());
    }

    @Test
    public void insertUsingCopiesTheSelectedRows() {
        seedShop();
        db.statement("create table archive (name text, total integer)");

        int copied = query.table("archive").insertUsing(List.of("name", "total"), q -> q.from("orders")
                .join("customers", "customers.id", "=", "orders.customer_id")
                .select("customers.name", "orders.total").where("orders.total", ">=", 10));

        assertEquals(2, copied);
        assertEquals(List.of("ann", "ann"), query.table("archive").pluck("name"));
    }

    @Test
    public void updateWithAJoinChangesOnlyTheJoinedRows() {
        seedShop();

        int changed = query.table("orders").join("customers", "customers.id", "=", "orders.customer_id")
                .where("customers.name", "ann").update(Map.of("total", 0));

        assertEquals(2, changed);
        assertEquals(5L, query.table("orders").sum("total").longValue());
    }

    private static boolean isAtLeast(String version, int[] wanted) {
        String[] parts = version.split("\\.");
        for (int i = 0; i < wanted.length; i++) {
            int part = i < parts.length ? Integer.parseInt(parts[i]) : 0;
            if (part != wanted[i]) {
                return part > wanted[i];
            }
        }
        return true;
    }
}

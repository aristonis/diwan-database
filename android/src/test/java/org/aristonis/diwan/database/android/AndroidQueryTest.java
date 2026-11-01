package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.values.Instants;
import org.aristonis.diwan.database.values.MoneyAmount;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidQueryTest {

    private static final int MONEY_SCALE = 2;

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
    public void moneyAndInstantRoundTrip() {
        MoneyAmount price = MoneyAmount.of(new BigDecimal("12.34"), MONEY_SCALE);
        Instant at = Instant.parse("2026-11-01T10:15:30.123456Z");

        query.table("items").insert(Map.of("name", "m", "price", price.toMinor(),
                "price_text", price.toText(), "at", at));
        Map<String, Object> row = query.table("items").first().orElseThrow();

        assertEquals(price, MoneyAmount.read(row.get("price"), MONEY_SCALE));
        assertEquals(price, MoneyAmount.read(row.get("price_text"), MONEY_SCALE));
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
}

package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelClock;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Casts over Android's SQLiteDatabase, under Robolectric: text, integer and real storage read as on SQLite JDBC. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidCastsTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123456Z");
    private static final Instant LATER = Instant.parse("2026-11-03T08:00:00Z");
    private static final Instant PAID = Instant.parse("2026-11-01T09:30:00Z");

    /** An order status stored by constant name. */
    public enum Status {
        OPEN, PAID
    }

    /** An order with a decimal, a json document, a datetime, an enum, a collection and an object cast. */
    public static final class Order extends Model {
        static final ModelQuery<Order> QUERY = ModelQuery.of(Order.class, Order::new);

        @Override
        protected List<String> fillable() {
            return List.of("total", "meta", "paid_at", "status", "tags", "opts");
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("total", Casts.of("decimal:2"));
            casts.put("meta", Casts.json());
            casts.put("paid_at", Casts.datetime());
            casts.put("status", Casts.enumOf(Status.class));
            casts.put("tags", Casts.collection());
            casts.put("opts", Casts.object());
            return casts;
        }
    }

    private SQLiteDatabase database;
    private QueryFactory db;
    private ModelQuery<Order> orders;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        Connection connection = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("orders", t -> {
            t.id();
            t.decimal("total", 8, 2).nullable();
            t.text("meta").nullable();
            t.dateTime("paid_at").nullable();
            t.string("status").nullable();
            t.text("tags").nullable();
            t.text("opts").nullable();
            t.timestamps();
        });
        orders = Order.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private Order createOrder() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tier", "gold");
        meta.put("tags", List.of(1L, 2L));
        return orders.create(row("total", new BigDecimal("19.5"), "meta", meta, "paid_at", PAID, "status",
                Status.PAID));
    }

    private Order createShapedOrder() {
        return orders.create(row("tags", new ArrayList<>(List.of(1L, 2L)), "opts",
                new LinkedHashMap<>(Map.of("a", 1L))));
    }

    @Test
    public void castsSurviveASaveAndAReload() {
        Order found = orders.findOrFail(createOrder().getKey());

        assertEquals(new BigDecimal("19.50"), found.getAttribute("total"));
        assertEquals(Map.of("tier", "gold", "tags", List.of(1L, 2L)), found.getAttribute("meta"));
        assertEquals(PAID, found.getAttribute("paid_at"));
        assertEquals(Status.PAID, found.getAttribute("status"));
        assertTrue(found.isClean());
    }

    @Test
    public void decimalFromRealColumn() {
        db.table("orders").insert(row("total", 0.1));

        Order found = orders.findOrFail(1);

        assertEquals(new BigDecimal("0.10"), found.getAttribute("total"));
        assertEquals("0.10", found.toMap().get("total"));
        found.setAttribute("total", new BigDecimal("0.10"));
        assertTrue(found.isClean());
    }

    @Test
    public void decimalFromIntegerColumn() {
        db.table("orders").insert(row("total", 12L));

        Order found = orders.findOrFail(1);

        assertEquals(new BigDecimal("12.00"), found.getAttribute("total"));
        found.setAttribute("total", "12");
        assertFalse(found.isDirty());
    }

    @Test
    public void saveWritesOnlyDirtyCast() {
        Order created = createOrder();
        Order found = Order.QUERY.on(db).withClock(ModelClock.fixed(LATER)).findOrFail(created.getKey());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tier", "silver");
        meta.put("tags", List.of(1L, 2L));

        found.setAttribute("meta", meta);
        assertTrue(found.save());

        assertEquals(Set.of("meta", "updated_at"), found.getChanges().keySet());
        Order reloaded = orders.findOrFail(created.getKey());
        assertEquals("silver", ((Map<?, ?>) reloaded.getAttribute("meta")).get("tier"));
        assertEquals(new BigDecimal("19.50"), reloaded.getAttribute("total"));
    }

    @Test
    public void toJsonUsesTheSerializedForms() {
        Order found = orders.findOrFail(createOrder().getKey());

        String json = found.toJson();

        assertTrue(json, json.contains("\"total\":\"19.50\""));
        assertTrue(json, json.contains("\"meta\":{\"tier\":\"gold\",\"tags\":[1,2]}"));
        assertTrue(json, json.contains("\"status\":\"PAID\""));
        assertTrue(json, json.contains("\"paid_at\":\"2026-11-01T09:30:00.000000Z\""));
    }

    @Test
    public void collectionAndObjectSurviveAReload() {
        Order found = orders.findOrFail(createShapedOrder().getKey());

        assertEquals(List.of(1L, 2L), found.getAttribute("tags"));
        assertEquals(Map.of("a", 1L), found.getAttribute("opts"));
        assertTrue(found.isClean());
    }

    @Test
    public void nullWriteStoresSqlNullAndWhereNullFindsIt() {
        Order created = createShapedOrder();
        Order found = orders.findOrFail(created.getKey());

        found.setAttribute("tags", null);
        found.setAttribute("opts", null);
        assertTrue(found.save());

        Order reloaded = orders.findOrFail(created.getKey());
        assertNull(reloaded.getRawOriginal("tags"));
        assertNull(reloaded.getRawOriginal("opts"));
        assertEquals(1, orders.query().whereNull("tags").count());
        assertEquals(1, orders.query().whereNull("opts").count());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void mutatingTheReadListAndMapSavesThem() {
        Order created = createShapedOrder();
        Order found = orders.findOrFail(created.getKey());

        ((List<Object>) found.getAttribute("tags")).add(3L);
        ((Map<String, Object>) found.getAttribute("opts")).put("b", 2L);
        assertTrue(found.isDirty("tags"));
        assertTrue(found.isDirty("opts"));
        assertTrue(found.save());

        Order reloaded = orders.findOrFail(created.getKey());
        assertEquals(List.of(1L, 2L, 3L), reloaded.getAttribute("tags"));
        assertEquals(Map.of("a", 1L, "b", 2L), reloaded.getAttribute("opts"));
    }

    @Test
    public void toJsonEmitsListAndMap() {
        Order found = orders.findOrFail(createShapedOrder().getKey());

        String json = found.toJson();

        assertTrue(json, json.contains("\"tags\":[1,2]"));
        assertTrue(json, json.contains("\"opts\":{\"a\":1}"));
    }
}

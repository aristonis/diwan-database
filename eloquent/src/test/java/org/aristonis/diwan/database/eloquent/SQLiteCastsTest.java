package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.SQLiteModelTest.SingleConnection;
import org.aristonis.diwan.database.eloquent.casts.AttributeEncrypter;
import org.aristonis.diwan.database.eloquent.casts.AttributeHasher;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Casts against a real in-memory SQLite database: storage round trips, dirty writes and JSON output. */
class SQLiteCastsTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123456Z");
    private static final Instant LATER = Instant.parse("2026-11-03T08:00:00Z");
    private static final Instant PAID = Instant.parse("2026-11-01T09:30:00Z");

    /** An order status stored by constant name. */
    enum Status {
        OPEN, PAID
    }

    /** An order with a decimal, a json document, a datetime, an enum, a collection and an object cast. */
    static final class Order extends Model {
        static final ModelQuery<Order> QUERY = ModelQuery.of(Order.class, Order::new);
        static FakeEncrypter encrypter;
        static FakeHasher hasher;

        @Override
        protected AttributeEncrypter attributeEncrypter() {
            return encrypter;
        }

        @Override
        protected AttributeHasher attributeHasher() {
            return hasher;
        }

        @Override
        protected List<String> fillable() {
            return Arrays.asList("total", "meta", "paid_at", "status", "tags", "opts", "secret", "payload",
                    "password");
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
            casts.put("secret", Casts.of("encrypted"));
            casts.put("payload", Casts.of("encrypted:json"));
            casts.put("password", Casts.of("hashed"));
            return casts;
        }
    }

    private java.sql.Connection jdbc;
    private QueryFactory db;
    private ModelQuery<Order> orders;

    @BeforeEach
    void open() throws SQLException {
        Order.encrypter = new FakeEncrypter();
        Order.hasher = new FakeHasher();
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        Connection connection = Connection.builder().name("sqlite").connectionProvider(new SingleConnection(jdbc))
                .build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("orders", t -> {
            t.id();
            t.decimal("total", 8, 2).nullable();
            t.text("meta").nullable();
            t.dateTime("paid_at").nullable();
            t.string("status").nullable();
            t.text("tags").nullable();
            t.text("opts").nullable();
            t.text("secret").nullable();
            t.text("payload").nullable();
            t.text("password").nullable();
            t.timestamps();
        });
        orders = Order.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    @AfterEach
    void close() throws SQLException {
        jdbc.close();
    }

    private Order createOrder() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tier", "gold");
        meta.put("tags", Arrays.asList(1L, 2L));
        return orders.create(row("total", new BigDecimal("19.5"), "meta", meta, "paid_at", PAID, "status",
                Status.PAID));
    }

    private Order createShapedOrder() {
        return orders.create(row("tags", new ArrayList<>(Arrays.asList(1L, 2L)), "opts",
                new LinkedHashMap<>(Map.of("a", 1L))));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> tagsOf(Order order) {
        return (List<Object>) order.getAttribute("tags");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optsOf(Order order) {
        return (Map<String, Object>) order.getAttribute("opts");
    }

    @Test
    void castsSurviveASaveAndAReload() {
        Order created = createOrder();

        Order found = orders.findOrFail(created.getKey());

        assertEquals(new BigDecimal("19.50"), found.getAttribute("total"));
        assertEquals(Map.of("tier", "gold", "tags", Arrays.asList(1L, 2L)), found.getAttribute("meta"));
        assertEquals(PAID, found.getAttribute("paid_at"));
        assertEquals(Status.PAID, found.getAttribute("status"));
        assertEquals("PAID", found.getRawOriginal("status"));
        assertTrue(found.isClean());
    }

    @Test
    void decimalFromRealColumn() {
        db.table("orders").insert(row("total", 0.1));

        Order found = orders.findOrFail(1);

        assertEquals(0.1, found.getAttributes().get("total"));
        assertEquals(new BigDecimal("0.10"), found.getAttribute("total"));
        assertEquals("0.10", found.toMap().get("total"));
        found.setAttribute("total", new BigDecimal("0.10"));
        assertTrue(found.isClean());
    }

    @Test
    void saveWritesOnlyDirtyCast() {
        Order created = createOrder();
        Order found = Order.QUERY.on(db).withClock(ModelClock.fixed(LATER)).findOrFail(created.getKey());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tier", "silver");
        meta.put("tags", Arrays.asList(1L, 2L));

        found.setAttribute("meta", meta);
        assertEquals(Set.of("meta"), found.getDirty().keySet());
        assertTrue(found.save());

        assertEquals(Set.of("meta", "updated_at"), found.getChanges().keySet());
        Order reloaded = orders.findOrFail(created.getKey());
        assertEquals("silver", ((Map<?, ?>) reloaded.getAttribute("meta")).get("tier"));
        assertEquals(new BigDecimal("19.50"), reloaded.getAttribute("total"));
        assertEquals(LATER, reloaded.getInstant("updated_at"));
        assertEquals(NOW.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), reloaded.getInstant("created_at"));
    }

    @Test
    void rewritingEqualCastValuesWritesNothing() {
        Order created = createOrder();
        Order found = Order.QUERY.on(db).withClock(ModelClock.fixed(LATER)).findOrFail(created.getKey());

        found.setAttribute("total", "19.500");
        found.setAttribute("status", "PAID");
        found.setAttribute("paid_at", PAID);

        assertFalse(found.isDirty());
        assertTrue(found.save());
        assertEquals(NOW.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                orders.findOrFail(created.getKey()).getInstant("updated_at"));
    }

    @Test
    void toJsonUsesTheSerializedForms() {
        Order found = orders.findOrFail(createOrder().getKey());

        Map<String, Object> map = found.toMap();
        String json = found.toJson();

        assertEquals("19.50", map.get("total"));
        assertEquals("PAID", map.get("status"));
        assertEquals("2026-11-01T09:30:00.000000Z", map.get("paid_at"));
        assertEquals(Map.of("tier", "gold", "tags", Arrays.asList(1L, 2L)), map.get("meta"));
        assertTrue(json.contains("\"total\":\"19.50\""), json);
        assertTrue(json.contains("\"meta\":{\"tier\":\"gold\",\"tags\":[1,2]}"), json);
        assertTrue(json.contains("\"status\":\"PAID\""), json);
        assertTrue(json.contains("\"paid_at\":\"2026-11-01T09:30:00.000000Z\""), json);
    }

    @Test
    void collectionAndObjectSurviveASaveAndAReload() {
        Order created = createShapedOrder();

        Order found = orders.findOrFail(created.getKey());

        assertEquals(Arrays.asList(1L, 2L), found.getAttribute("tags"));
        assertEquals(Map.of("a", 1L), found.getAttribute("opts"));
        assertEquals("[1,2]", found.getRawOriginal("tags"));
        assertEquals("{\"a\":1}", found.getRawOriginal("opts"));
        assertTrue(found.isClean());
    }

    @Test
    void nullWriteStoresSqlNullAndWhereNullFindsIt() {
        Order created = createShapedOrder();
        Order found = orders.findOrFail(created.getKey());

        found.setAttribute("tags", null);
        found.setAttribute("opts", null);
        assertTrue(found.save());

        Order reloaded = orders.findOrFail(created.getKey());
        assertNull(reloaded.getRawOriginal("tags"));
        assertNull(reloaded.getRawOriginal("opts"));
        assertNull(reloaded.getAttribute("tags"));
        assertEquals(1, orders.query().whereNull("tags").count());
        assertEquals(1, orders.query().whereNull("opts").count());
    }

    @Test
    void mutatingTheReadListAndMapSavesThem() {
        Order created = createShapedOrder();
        Order found = orders.findOrFail(created.getKey());

        tagsOf(found).add(3L);
        optsOf(found).put("b", 2L);
        assertEquals(Set.of("tags", "opts"), found.getDirty().keySet());
        assertTrue(found.save());

        Order reloaded = orders.findOrFail(created.getKey());
        assertEquals(Arrays.asList(1L, 2L, 3L), reloaded.getAttribute("tags"));
        assertEquals(Map.of("a", 1L, "b", 2L), reloaded.getAttribute("opts"));
    }

    @Test
    void rewritingEqualCollectionWritesNothing() {
        Order created = createShapedOrder();
        Order found = Order.QUERY.on(db).withClock(ModelClock.fixed(LATER)).findOrFail(created.getKey());

        found.setAttribute("tags", new ArrayList<>(Arrays.asList(1L, 2L)));
        found.setAttribute("opts", new LinkedHashMap<>(Map.of("a", 1L)));

        assertFalse(found.isDirty());
        assertTrue(found.save());
        assertEquals(NOW.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                orders.findOrFail(created.getKey()).getInstant("updated_at"));
    }

    @Test
    void toJsonEmitsListAndMap() {
        Order found = orders.findOrFail(createShapedOrder().getKey());

        String json = found.toJson();

        assertEquals(Arrays.asList(1L, 2L), found.toMap().get("tags"));
        assertEquals(Map.of("a", 1L), found.toMap().get("opts"));
        assertTrue(json.contains("\"tags\":[1,2]"), json);
        assertTrue(json.contains("\"opts\":{\"a\":1}"), json);
    }

    private Order createEncryptedOrder() {
        return orders.create(row("secret", "hush", "payload", new LinkedHashMap<>(Map.of("a", 1L))));
    }

    @Test
    void encryptedValuesStoreCipherTextAndReadPlain() {
        Order created = createEncryptedOrder();

        Map<String, Object> raw = db.table("orders").first().get();
        Order found = orders.findOrFail(created.getKey());

        assertNotEquals("hush", raw.get("secret"));
        assertFalse(((String) raw.get("secret")).contains("hush"));
        assertNotEquals("{\"a\":1}", raw.get("payload"));
        assertEquals("hush", found.getAttribute("secret"));
        assertEquals(Map.of("a", 1L), found.getAttribute("payload"));
        assertTrue(found.isClean());
    }

    @Test
    void encryptedNullStoresSqlNullAndWhereNullFindsIt() {
        Order created = createEncryptedOrder();
        Order found = orders.findOrFail(created.getKey());
        int encryptsBefore = Order.encrypter.encrypts;

        found.setAttribute("secret", null);
        found.setAttribute("payload", null);
        assertTrue(found.save());

        Order reloaded = orders.findOrFail(created.getKey());
        assertNull(reloaded.getRawOriginal("secret"));
        assertNull(reloaded.getAttribute("secret"));
        assertEquals(1, orders.query().whereNull("secret").count());
        assertEquals(1, orders.query().whereNull("payload").count());
        assertEquals(encryptsBefore, Order.encrypter.encrypts);
    }

    @Test
    void rewritingEqualEncryptedValuesWritesNothing() {
        Order created = createEncryptedOrder();
        Order found = Order.QUERY.on(db).withClock(ModelClock.fixed(LATER)).findOrFail(created.getKey());
        Object storedCipher = found.getRawOriginal("secret");

        found.setAttribute("secret", "hush");
        found.setAttribute("payload", new LinkedHashMap<>(Map.of("a", 1L)));

        assertTrue(found.getDirty().isEmpty());
        assertTrue(found.save());
        assertEquals(storedCipher, orders.findOrFail(created.getKey()).getRawOriginal("secret"));
    }

    @Test
    void mutatingTheReadEncryptedMapSavesIt() {
        Order created = createEncryptedOrder();
        Order found = orders.findOrFail(created.getKey());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) found.getAttribute("payload");
        payload.put("b", 2L);
        assertEquals(Set.of("payload"), found.getDirty().keySet());
        assertTrue(found.save());

        Order reloaded = orders.findOrFail(created.getKey());
        assertEquals(Map.of("a", 1L, "b", 2L), reloaded.getAttribute("payload"));
    }

    @Test
    void toJsonHoldsDecryptedValues() {
        Order found = orders.findOrFail(createEncryptedOrder().getKey());

        String json = found.toJson();

        assertEquals("hush", found.toMap().get("secret"));
        assertEquals(Map.of("a", 1L), found.toMap().get("payload"));
        assertTrue(json.contains("\"secret\":\"hush\""), json);
        assertTrue(json.contains("\"payload\":{\"a\":1}"), json);
        assertFalse(json.contains((String) found.getRawOriginal("secret")), json);
    }

    @Test
    void encrypterFailureSurfacesAsInvalidCastException() {
        Order found = orders.findOrFail(createEncryptedOrder().getKey());
        IllegalArgumentException failure = new IllegalArgumentException("bad key");
        Order.encrypter.decryptFailure = failure;

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> found.getAttribute("secret"));

        assertSame(failure, e.getCause());
        assertEquals("secret", e.getKey());
    }

    @Test
    void hashedValuesStoreTheHashAndReadItBack() {
        Order created = orders.create(row("password", "hunter2"));

        Map<String, Object> raw = db.table("orders").first().get();
        Order found = orders.findOrFail(created.getKey());

        String stored = (String) raw.get("password");
        assertNotEquals("hunter2", stored);
        assertTrue(Order.hasher.verify("hunter2", stored));
        assertEquals(1, Order.hasher.hashCalls);
        assertEquals(stored, found.getAttribute("password"));
        assertTrue(found.isClean());
        assertEquals(1, Order.hasher.hashCalls);
    }

    @Test
    void hashedNullStoresSqlNullAndWhereNullFindsIt() {
        Order created = orders.create(row("password", "hunter2"));
        Order found = orders.findOrFail(created.getKey());
        int hashesBefore = Order.hasher.hashCalls;

        found.setAttribute("password", null);
        assertTrue(found.save());

        Order reloaded = orders.findOrFail(created.getKey());
        assertNull(reloaded.getRawOriginal("password"));
        assertNull(reloaded.getAttribute("password"));
        assertEquals(1, orders.query().whereNull("password").count());
        assertEquals(hashesBefore, Order.hasher.hashCalls);
    }

    @Test
    void settingTheSamePlainAgainIsDirtyAndSaves() {
        Order created = orders.create(row("password", "hunter2"));
        Order found = Order.QUERY.on(db).withClock(ModelClock.fixed(LATER)).findOrFail(created.getKey());
        Object before = found.getRawOriginal("password");

        found.setAttribute("password", "hunter2");

        assertEquals(Set.of("password"), found.getDirty().keySet());
        assertTrue(found.save());
        Object after = orders.findOrFail(created.getKey()).getRawOriginal("password");
        assertNotEquals(before, after);
        assertTrue(Order.hasher.verify("hunter2", (String) after));
    }

    @Test
    void anAlreadyHashedValueIsStoredAsGiven() {
        String hash = Order.hasher.fakeFrom("hunter2", "bcrypt", 10, 64, 3, 2);

        Order created = orders.create(row("password", hash));

        assertEquals(hash, db.table("orders").first().get().get("password"));
        assertEquals(hash, orders.findOrFail(created.getKey()).getAttribute("password"));
        assertEquals(0, Order.hasher.hashCalls);
    }

    @Test
    void aNonStringValueFailsWithInvalidCastException() {
        Order order = new Order();

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> order.setAttribute("password", 424242));

        assertEquals("hashed", e.getCastName());
        assertEquals("password", e.getKey());
        assertTrue(e.getCause() instanceof IllegalArgumentException, String.valueOf(e.getCause()));
        assertEquals(0, Order.hasher.hashCalls);
    }
}

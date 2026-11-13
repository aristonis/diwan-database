package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelClock;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.casts.AttributeEncrypter;
import org.aristonis.diwan.database.eloquent.casts.AttributeHasher;
import org.aristonis.diwan.database.eloquent.casts.BinaryIds;
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

    /** Base64 stands in for a user cipher: this driver test checks storage and cast wiring, not secrecy. */
    private static final class Encrypter implements AttributeEncrypter {
        @Override
        public String encrypt(String plain) {
            return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decrypt(String cipher) {
            return new String(Base64.getDecoder().decode(cipher), StandardCharsets.UTF_8);
        }
    }

    /**
     * A reversible, salted stand-in for a user hasher: the counter makes a second hash of one plain differ,
     * as a real salt does. This driver test checks storage and cast wiring, not hashing.
     */
    private static final class Hasher implements AttributeHasher {
        private int salt;

        @Override
        public String hash(String plain) {
            salt++;
            String payload = Base64.getUrlEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
            return "$fake$" + salt + "$" + payload;
        }

        @Override
        public boolean isHashed(String value) {
            return value.startsWith("$fake$");
        }

        boolean verify(String plain, String hashed) {
            String payload = hashed.substring(hashed.lastIndexOf('$') + 1);
            return plain.equals(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
        }
    }

    /** An order with a decimal, a json document, a datetime, an enum, a collection, an object and encrypted casts. */
    public static final class Order extends Model {
        static final ModelQuery<Order> QUERY = ModelQuery.of(Order.class, Order::new);
        static Hasher hasher;

        @Override
        protected List<String> fillable() {
            return List.of("total", "meta", "paid_at", "status", "tags", "opts", "secret", "payload", "password",
                    "uid", "ref");
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
            casts.put("uid", Casts.of("binary:uuid"));
            casts.put("ref", Casts.of("binary:ulid"));
            return casts;
        }

        @Override
        protected AttributeEncrypter attributeEncrypter() {
            return new Encrypter();
        }

        @Override
        protected AttributeHasher attributeHasher() {
            return hasher;
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
            t.text("secret").nullable();
            t.text("payload").nullable();
            t.text("password").nullable();
            t.binary("uid").nullable();
            t.binary("ref").nullable();
            t.timestamps();
        });
        Order.hasher = new Hasher();
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

    private Order createEncryptedOrder() {
        return orders.create(row("secret", "top secret", "payload", new LinkedHashMap<>(Map.of("a", 1L))));
    }

    @Test
    public void encryptedValuesSurviveAReload() {
        Order found = orders.findOrFail(createEncryptedOrder().getKey());

        assertEquals("top secret", found.getAttribute("secret"));
        assertEquals(Map.of("a", 1L), found.getAttribute("payload"));
        assertTrue(found.isClean());
    }

    @Test
    public void encryptedNullStoresSqlNullAndWhereNullFindsIt() {
        Order created = createEncryptedOrder();
        Order found = orders.findOrFail(created.getKey());

        found.setAttribute("secret", null);
        assertTrue(found.save());

        Order reloaded = orders.findOrFail(created.getKey());
        assertNull(reloaded.getRawOriginal("secret"));
        assertEquals(1, orders.query().whereNull("secret").count());
    }

    @Test
    public void rewritingEqualEncryptedValueIsClean() {
        Order found = orders.findOrFail(createEncryptedOrder().getKey());

        found.setAttribute("secret", "top secret");

        assertFalse(found.isDirty());
    }

    @Test
    public void encryptedValuesSerializeDecrypted() {
        Order found = orders.findOrFail(createEncryptedOrder().getKey());

        String json = found.toJson();

        assertTrue(json, json.contains("\"secret\":\"top secret\""));
        assertTrue(json, json.contains("\"payload\":{\"a\":1}"));
    }

    private Order createHashedOrder() {
        return orders.create(row("password", "hunter2"));
    }

    @Test
    public void hashedValuesStoreTheHashAndReadItBack() {
        Order created = createHashedOrder();
        Order found = orders.findOrFail(created.getKey());
        String stored = (String) found.getRawOriginal("password");

        assertNotEquals("hunter2", stored);
        assertTrue(Order.hasher.verify("hunter2", stored));
        assertEquals(stored, found.getAttribute("password"));
        assertTrue(found.isClean());
    }

    @Test
    public void hashedNullStoresSqlNullAndWhereNullFindsIt() {
        Order created = createHashedOrder();
        Order found = orders.findOrFail(created.getKey());

        found.setAttribute("password", null);
        assertTrue(found.save());

        Order reloaded = orders.findOrFail(created.getKey());
        assertNull(reloaded.getRawOriginal("password"));
        assertEquals(1, orders.query().whereNull("password").count());
    }

    @Test
    public void settingTheSamePlainAgainIsDirtyAndSaves() {
        Order created = createHashedOrder();
        Order found = orders.findOrFail(created.getKey());
        String before = (String) found.getRawOriginal("password");

        found.setAttribute("password", "hunter2");

        assertTrue(found.isDirty("password"));
        assertTrue(found.save());
        String after = (String) orders.findOrFail(created.getKey()).getRawOriginal("password");
        assertNotEquals(before, after);
        assertTrue(Order.hasher.verify("hunter2", after));
    }

    @Test
    public void toJsonReturnsTheStoredHash() {
        Order found = orders.findOrFail(createHashedOrder().getKey());
        String stored = (String) found.getRawOriginal("password");

        String json = found.toJson();

        assertTrue(json, json.contains("\"password\":\"" + stored + "\""));
        assertFalse(json, json.contains("hunter2"));
    }

    @Test
    public void binaryUuidRoundTripsThroughABlob() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        Order created = orders.create(row("uid", id));
        Order found = orders.findOrFail(created.getKey());

        assertArrayEquals(BinaryIds.uuidBytes(id), (byte[]) found.getRawOriginal("uid"));
        assertEquals(id, found.getAttribute("uid"));
        assertTrue(found.isClean());
    }

    @Test
    public void binaryUlidRoundTripsThroughABlob() {
        String upper = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        Order created = orders.create(row("ref", upper));
        Order found = orders.findOrFail(created.getKey());

        assertArrayEquals(BinaryIds.ulidBytes(upper), (byte[]) found.getRawOriginal("ref"));
        assertEquals("01arz3ndektsv4rrffq69g5fav", found.getAttribute("ref"));
        assertTrue(found.isClean());
    }

    @Test
    public void binaryNullStoresSqlNullAndWhereNullFindsIt() {
        Order created = orders.create(row("uid", UUID.fromString("550e8400-e29b-41d4-a716-446655440000")));
        Order found = orders.findOrFail(created.getKey());

        found.setAttribute("uid", null);
        assertTrue(found.save());

        Order reloaded = orders.findOrFail(created.getKey());
        assertNull(reloaded.getRawOriginal("uid"));
        assertEquals(1, orders.query().whereNull("uid").count());
    }

    @Test
    public void whereWithBinaryIdsBytesFindsTheRow() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String ulid = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        orders.create(row("uid", id, "ref", ulid));

        assertEquals(1, orders.query().where("uid", BinaryIds.uuidBytes(id)).count());
        assertEquals(1, orders.query().where("ref", BinaryIds.ulidBytes(ulid)).count());
        assertEquals(0, orders.query()
                .where("uid", BinaryIds.uuidBytes(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))).count());
    }

    @Test
    public void aUuidWithValidUtf8BytesRoundTrips() {
        UUID id = new UUID(0x61626364656644c3L, 0xa96768696a6b6c6dL);
        Order created = orders.create(row("uid", id));
        Order found = orders.findOrFail(created.getKey());

        assertArrayEquals(BinaryIds.uuidBytes(id), (byte[]) found.getRawOriginal("uid"));
        assertEquals(id, found.getAttribute("uid"));
        assertTrue(found.isClean());
    }

    @Test
    public void changingABinaryValueIsDirtyAndUpdatesTheRow() {
        UUID first = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID second = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        Order created = orders.create(row("uid", first));
        Order found = orders.findOrFail(created.getKey());

        found.setAttribute("uid", second);

        assertEquals(Set.of("uid"), found.getDirty().keySet());
        assertTrue(found.save());
        Order reloaded = orders.findOrFail(created.getKey());
        assertEquals(second, reloaded.getAttribute("uid"));
    }
}

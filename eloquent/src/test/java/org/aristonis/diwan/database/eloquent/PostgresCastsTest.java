package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.casts.AttributeEncrypter;
import org.aristonis.diwan.database.eloquent.casts.AttributeHasher;
import org.aristonis.diwan.database.eloquent.casts.BinaryIds;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.aristonis.diwan.database.eloquent.exception.JsonException;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Casts against a throwaway PostgreSQL; skipped when the server binaries are missing. */
class PostgresCastsTest {

    /** A model over one column of each Postgres type under test. */
    static final class Probe extends Model {
        static final ModelQuery<Probe> QUERY = ModelQuery.of(Probe.class, Probe::new);
        static FakeEncrypter encrypter;
        static FakeHasher hasher;

        @Override
        protected String table() {
            return "probes";
        }

        @Override
        protected AttributeEncrypter attributeEncrypter() {
            return encrypter;
        }

        @Override
        protected AttributeHasher attributeHasher() {
            return hasher;
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("amount", Casts.of("decimal:2"));
            casts.put("strict", Casts.decimal(12, 2));
            casts.put("seen_at", Casts.datetime());
            casts.put("seen_tz", Casts.datetime());
            casts.put("day", Casts.date());
            casts.put("ratio", Casts.floating());
            casts.put("doc", Casts.json());
            casts.put("docb", Casts.json());
            casts.put("instant_at", Casts.instant());
            casts.put("tags", Casts.collection());
            casts.put("opts", Casts.object());
            casts.put("secret", Casts.of("encrypted"));
            casts.put("payload", Casts.of("encrypted:json"));
            casts.put("password", Casts.of("hashed"));
            casts.put("uid", Casts.of("binary:uuid"));
            casts.put("ref", Casts.of("binary:ulid"));
            return casts;
        }
    }

    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void open() {
        connection = PostgresServer.newDatabase();
        db = PostgresQuery.of(connection);
        Probe.encrypter = new FakeEncrypter();
        Probe.hasher = new FakeHasher();
        connection.statement("create table probes (id bigserial primary key, amount numeric(10,2), "
                + "seen_at timestamp(6), seen_tz timestamptz(6), day date, ratio float8, doc json, docb jsonb, "
                + "instant_at timestamptz(6), strict numeric(12,2), tags jsonb, opts json, "
                + "secret text, payload text, password text, uid bytea, ref bytea)");
    }

    private void insertSample() {
        connection.statement("insert into probes (amount, seen_at, seen_tz, day, ratio, doc, docb) values "
                + "(12.5, '2026-11-02 10:15:30.123456', '2026-11-02 10:15:30.123456+00', '2026-11-02', "
                + "'Infinity', '{\"b\":1,\"a\":2}', '{\"b\":1,\"a\":2}')");
    }

    @Test
    void columnTypesRead() {
        insertSample();

        Probe probe = Probe.QUERY.on(db).findOrFail(1);

        Map<String, Object> raw = probe.getAttributes();
        assertTrue(raw.get("amount") instanceof BigDecimal);
        assertTrue(raw.get("seen_at") instanceof LocalDateTime);
        assertTrue(raw.get("day") instanceof LocalDate);
        assertTrue(raw.get("seen_tz") instanceof OffsetDateTime);
        assertEquals(Double.POSITIVE_INFINITY, raw.get("ratio"));
        Instant moment = Instant.parse("2026-11-02T10:15:30.123456Z");
        assertEquals(new BigDecimal("12.50"), probe.getAttribute("amount"));
        assertEquals(moment, probe.getAttribute("seen_at"));
        assertEquals(moment, probe.getAttribute("seen_tz"));
        assertEquals(LocalDate.of(2026, 11, 2), probe.getAttribute("day"));
        assertEquals(Double.POSITIVE_INFINITY, probe.getAttribute("ratio"));
        assertTrue(probe.isClean());
    }

    @Test
    void infinityBreaksToJson() {
        insertSample();

        Probe probe = Probe.QUERY.on(db).findOrFail(1);

        assertThrows(JsonException.class, probe::toJson);
    }

    @Test
    void jsonAndJsonbValuesReachTheCastAsString() {
        insertSample();

        Probe probe = Probe.QUERY.on(db).findOrFail(1);

        assertTrue(probe.getAttributes().get("doc") instanceof String);
        assertTrue(probe.getAttributes().get("docb") instanceof String);
        assertEquals(Map.of("a", 2L, "b", 1L), probe.getAttribute("doc"));
        assertEquals(Map.of("a", 2L, "b", 1L), probe.getAttribute("docb"));
    }

    @Test
    void jsonbReorderStaysClean() {
        insertSample();
        Probe probe = Probe.QUERY.on(db).findOrFail(1);
        Map<String, Object> sameMap = new LinkedHashMap<>();
        sameMap.put("b", 1L);
        sameMap.put("a", 2L);

        probe.setAttribute("docb", sameMap);
        assertFalse(probe.isDirty("docb"));
        assertTrue(probe.save());
        probe.refresh();
        assertTrue(probe.isClean());
        Probe again = Probe.QUERY.on(db).findOrFail(1);
        again.setAttribute("docb", sameMap);
        assertTrue(again.isClean());
    }

    @Test
    void jsonbChangeRoundTripsThroughSave() {
        insertSample();
        Probe probe = Probe.QUERY.on(db).findOrFail(1);
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("z", Arrays.asList(1L, 2L));
        changed.put("a", "x");

        probe.setAttribute("docb", changed);
        probe.setAttribute("doc", changed);
        assertTrue(probe.save());

        Probe reloaded = Probe.QUERY.on(db).findOrFail(1);
        assertEquals(changed, reloaded.getAttribute("docb"));
        assertEquals(changed, reloaded.getAttribute("doc"));
        assertTrue(reloaded.isClean());
    }

    @Test
    void creatingFromCastValuesWritesTypedColumns() {
        Probe created = Probe.QUERY.on(db).create(row("seen_at", Instant.parse("2026-11-02T10:15:30Z"),
                "day", LocalDate.of(2026, 11, 2), "docb", Map.of("k", "v")));

        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(Instant.parse("2026-11-02T10:15:30Z"), found.getAttribute("seen_at"));
        assertEquals(LocalDate.of(2026, 11, 2), found.getAttribute("day"));
        assertEquals(Map.of("k", "v"), found.getAttribute("docb"));
    }

    @Test
    void instantCastStoresTimestamptz() {
        Instant moment = Instant.parse("2026-11-02T10:15:30.123456Z");

        Probe created = Probe.QUERY.on(db).create(row("instant_at", moment));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(moment, found.getAttribute("instant_at"));
    }

    @Test
    void strictDecimalRoundTrip() {
        Probe created = Probe.QUERY.on(db).create(row("strict", new BigDecimal("12345.6")));

        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(new BigDecimal("12345.60"), found.getAttribute("strict"));
    }

    @Test
    void collectionAndObjectReadFromJsonAndJsonb() {
        connection.statement("insert into probes (tags, opts) values ('[1, {\"a\": 2}]', '{\"b\":1,\"a\":2}')");

        Probe probe = Probe.QUERY.on(db).findOrFail(1);

        assertTrue(probe.getAttributes().get("tags") instanceof String);
        assertTrue(probe.getAttributes().get("opts") instanceof String);
        assertEquals(Arrays.asList(1L, Map.of("a", 2L)), probe.getAttribute("tags"));
        assertEquals(Map.of("a", 2L, "b", 1L), probe.getAttribute("opts"));
        assertTrue(probe.isClean());
    }

    @Test
    void jsonbReorderOfAnObjectStaysCleanAndListOrderIsDirty() {
        connection.statement("insert into probes (tags, opts) values ('[1,2]', '{\"b\":1,\"a\":2}')");
        Probe probe = Probe.QUERY.on(db).findOrFail(1);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("a", 2L);
        reordered.put("b", 1L);

        probe.setAttribute("opts", reordered);
        assertFalse(probe.isDirty("opts"));
        probe.setAttribute("tags", new ArrayList<>(Arrays.asList(2L, 1L)));

        assertTrue(probe.isDirty("tags"));
    }

    @Test
    void nullWriteStoresSqlNullAndWhereNullFindsIt() {
        Probe created = Probe.QUERY.on(db).create(row("tags", Arrays.asList(1L), "opts", Map.of("a", 1L)));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        found.setAttribute("tags", null);
        found.setAttribute("opts", null);
        assertTrue(found.save());

        Probe reloaded = Probe.QUERY.on(db).findOrFail(created.getKey());
        assertNull(reloaded.getAttributes().get("tags"));
        assertNull(reloaded.getAttributes().get("opts"));
        assertEquals(1, Probe.QUERY.on(db).query().whereNull("tags").count());
        assertEquals(1, Probe.QUERY.on(db).query().whereNull("opts").count());
    }

    @Test
    void collectionAndObjectMutationRoundTrips() {
        Probe created = Probe.QUERY.on(db).create(row("tags", new ArrayList<>(Arrays.asList(1L)), "opts",
                new LinkedHashMap<>(Map.of("a", 1L))));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) found.getAttribute("tags");
        @SuppressWarnings("unchecked")
        Map<String, Object> opts = (Map<String, Object>) found.getAttribute("opts");
        tags.add(2L);
        opts.put("b", 2L);
        assertTrue(found.isDirty("tags"));
        assertTrue(found.isDirty("opts"));
        assertTrue(found.save());

        Probe reloaded = Probe.QUERY.on(db).findOrFail(created.getKey());
        assertEquals(Arrays.asList(1L, 2L), reloaded.getAttribute("tags"));
        assertEquals(Map.of("a", 1L, "b", 2L), reloaded.getAttribute("opts"));
        assertTrue(reloaded.isClean());
    }

    @Test
    void encryptedValuesStoreCiphertextAndReadPlain() {
        Probe created = Probe.QUERY.on(db).create(row("secret", "top secret", "payload", Map.of("a", 1L)));

        Map<String, Object> raw = db.table("probes").first().get();
        assertNotEquals("top secret", raw.get("secret"));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        assertEquals("top secret", found.getAttribute("secret"));
        assertEquals(Map.of("a", 1L), found.getAttribute("payload"));
        assertTrue(found.isClean());
    }

    @Test
    void encryptedNullStoresSqlNullAndWhereNullFindsIt() {
        Probe created = Probe.QUERY.on(db).create(row("secret", "top secret"));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());
        int encryptsBefore = Probe.encrypter.encrypts;

        found.setAttribute("secret", null);
        assertTrue(found.save());

        Probe reloaded = Probe.QUERY.on(db).findOrFail(created.getKey());
        assertNull(reloaded.getAttributes().get("secret"));
        assertEquals(encryptsBefore, Probe.encrypter.encrypts);
        assertEquals(1, Probe.QUERY.on(db).query().whereNull("secret").count());
    }

    @Test
    void rewritingEqualEncryptedValueWritesNothing() {
        Probe created = Probe.QUERY.on(db).create(row("secret", "top secret"));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());
        Map<String, Object> before = db.table("probes").first().get();

        found.setAttribute("secret", "top secret");

        assertTrue(found.getDirty().isEmpty());
        assertTrue(found.save());
        assertEquals(before.get("secret"), db.table("probes").first().get().get("secret"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mutatingTheReadEncryptedMapSavesIt() {
        Probe created = Probe.QUERY.on(db).create(row("payload", new LinkedHashMap<>(Map.of("a", 1L))));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());
        Map<String, Object> payload = (Map<String, Object>) found.getAttribute("payload");

        payload.put("b", 2L);
        assertTrue(found.isDirty("payload"));
        assertTrue(found.save());

        Probe reloaded = Probe.QUERY.on(db).findOrFail(created.getKey());
        assertEquals(Map.of("a", 1L, "b", 2L), reloaded.getAttribute("payload"));
        assertTrue(reloaded.isClean());
    }

    @Test
    void toJsonHoldsDecryptedValues() {
        Probe created = Probe.QUERY.on(db).create(row("secret", "top secret", "payload", Map.of("a", 1L)));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        String json = found.toJson();

        assertTrue(json.contains("\"secret\":\"top secret\""));
        assertTrue(json.contains("\"payload\":{\"a\":1}"));
    }

    @Test
    void encrypterFailureSurfacesAsInvalidCastException() {
        Probe created = Probe.QUERY.on(db).create(row("secret", "top secret"));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());
        RuntimeException failure = new IllegalStateException("vault unavailable");
        Probe.encrypter.decryptFailure = failure;

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> found.getAttribute("secret"));

        assertSame(failure, e.getCause());
    }

    @Test
    void hashedValuesStoreTheHashAndReadItBack() {
        Probe created = Probe.QUERY.on(db).create(row("password", "hunter2"));

        String stored = (String) db.table("probes").first().get().get("password");
        assertNotEquals("hunter2", stored);
        assertTrue(Probe.hasher.verify("hunter2", stored));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(stored, found.getAttribute("password"));
        assertTrue(found.isClean());
    }

    @Test
    void hashedNullStoresSqlNullAndWhereNullFindsIt() {
        Probe created = Probe.QUERY.on(db).create(row("password", "hunter2"));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        found.setAttribute("password", null);
        assertTrue(found.save());

        Probe reloaded = Probe.QUERY.on(db).findOrFail(created.getKey());
        assertNull(reloaded.getAttributes().get("password"));
        assertEquals(1, Probe.QUERY.on(db).query().whereNull("password").count());
    }

    @Test
    void settingTheSamePlainAgainIsDirtyAndSaves() {
        Probe created = Probe.QUERY.on(db).create(row("password", "hunter2"));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());
        String before = (String) db.table("probes").first().get().get("password");

        found.setAttribute("password", "hunter2");

        assertTrue(found.isDirty("password"));
        assertTrue(found.save());
        String after = (String) db.table("probes").first().get().get("password");
        assertNotEquals(before, after);
        assertTrue(Probe.hasher.verify("hunter2", after));
    }

    @Test
    void toJsonReturnsTheStoredHash() {
        Probe created = Probe.QUERY.on(db).create(row("password", "hunter2"));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());
        String stored = (String) found.getAttributes().get("password");

        String json = found.toJson();

        assertTrue(json.contains("\"password\":\"" + stored + "\""));
        assertFalse(json.contains("hunter2"));
    }

    @Test
    void binaryUuidRoundTripsThroughABytea() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        Probe created = Probe.QUERY.on(db).create(row("uid", id));

        byte[] raw = (byte[]) db.table("probes").first().get().get("uid");
        assertArrayEquals(BinaryIds.uuidBytes(id), raw);
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(id, found.getAttribute("uid"));
        assertTrue(found.isClean());
    }

    @Test
    void binaryUlidRoundTripsThroughABytea() {
        String upper = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        Probe created = Probe.QUERY.on(db).create(row("ref", upper));

        byte[] raw = (byte[]) db.table("probes").first().get().get("ref");
        assertArrayEquals(BinaryIds.ulidBytes(upper), raw);
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        assertEquals("01arz3ndektsv4rrffq69g5fav", found.getAttribute("ref"));
        assertTrue(found.isClean());
    }

    @Test
    void binaryNullStoresSqlNullAndWhereNullFindsIt() {
        Probe created = Probe.QUERY.on(db).create(row("uid", UUID.fromString("550e8400-e29b-41d4-a716-446655440000")));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        found.setAttribute("uid", null);
        assertTrue(found.save());

        Probe reloaded = Probe.QUERY.on(db).findOrFail(created.getKey());
        assertNull(reloaded.getAttributes().get("uid"));
        assertEquals(1, Probe.QUERY.on(db).query().whereNull("uid").count());
    }

    @Test
    void whereWithBinaryIdsBytesFindsTheRow() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String ulid = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        Probe.QUERY.on(db).create(row("uid", id, "ref", ulid));

        assertEquals(1, Probe.QUERY.on(db).query().where("uid", BinaryIds.uuidBytes(id)).count());
        assertEquals(1, Probe.QUERY.on(db).query().where("ref", BinaryIds.ulidBytes(ulid)).count());
        assertEquals(0, Probe.QUERY.on(db).query()
                .where("uid", BinaryIds.uuidBytes(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))).count());
    }

    @Test
    void aUuidWithValidUtf8BytesRoundTrips() {
        UUID id = new UUID(0x61626364656644c3L, 0xa96768696a6b6c6dL);
        Probe created = Probe.QUERY.on(db).create(row("uid", id));

        byte[] raw = (byte[]) db.table("probes").first().get().get("uid");
        assertArrayEquals(BinaryIds.uuidBytes(id), raw);
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(id, found.getAttribute("uid"));
        assertTrue(found.isClean());
    }

    @Test
    void changingABinaryValueIsDirtyAndUpdatesTheRow() {
        UUID first = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID second = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        Probe created = Probe.QUERY.on(db).create(row("uid", first));
        Probe found = Probe.QUERY.on(db).findOrFail(created.getKey());

        found.setAttribute("uid", second);

        assertEquals(Set.of("uid"), found.getDirty().keySet());
        assertTrue(found.save());
        Probe reloaded = Probe.QUERY.on(db).findOrFail(created.getKey());
        assertEquals(second, reloaded.getAttribute("uid"));
    }
}

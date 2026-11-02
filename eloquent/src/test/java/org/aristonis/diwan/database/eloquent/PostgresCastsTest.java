package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.json.JsonException;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Casts against a throwaway PostgreSQL; skipped when the server binaries are missing. */
class PostgresCastsTest {

    /** A model over one column of each Postgres type under test. */
    static final class Probe extends Model {
        static final ModelQuery<Probe> QUERY = ModelQuery.of(Probe.class, Probe::new);

        @Override
        protected String table() {
            return "probes";
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
            casts.put("seen_at", Casts.datetime());
            casts.put("seen_tz", Casts.datetime());
            casts.put("day", Casts.date());
            casts.put("ratio", Casts.floating());
            casts.put("doc", Casts.json());
            casts.put("docb", Casts.json());
            return casts;
        }
    }

    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void open() {
        connection = PostgresServer.newDatabase();
        db = PostgresQuery.of(connection);
        connection.statement("create table probes (id bigserial primary key, amount numeric(10,2), "
                + "seen_at timestamp(6), seen_tz timestamptz(6), day date, ratio float8, doc json, docb jsonb)");
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
}

package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Factory insert: one bulk statement, casts and keys applied once, no events and no hooks. */
class FactoryInsertTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30Z");
    private static final String NOW_TEXT = "2026-11-02 10:15:30";

    /** An item with an array cast, a hidden attribute and timestamps. */
    static final class Item extends Model {
        static final ModelQuery<Item> QUERY = ModelQuery.of(Item.class, Item::new);

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected List<String> hidden() {
            return List.of("secret");
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("tags", Casts.of("array"), "options", Casts.of("array"));
        }
    }

    /** A UUID-keyed model. */
    static final class Ticket extends Model {
        static final ModelQuery<Ticket> QUERY = ModelQuery.of(Ticket.class, Ticket::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.UUID;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private SqliteModelDb database;

    @BeforeEach
    void open() throws SQLException {
        database = SqliteModelDb.open();
        database.sql("create table items (id integer primary key autoincrement, name text, secret text, tags text,"
                + " options text, created_at text, updated_at text)");
        database.sql("create table tickets (id text primary key, code text, created_at text, updated_at text)");
    }

    @AfterEach
    void close() throws SQLException {
        Item.QUERY.events().forget();
        database.close();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private Factory<Item> items() {
        return Factory.of(Item.QUERY.on(database.db).withClock(ModelClock.fixed(NOW)), () -> map("name", "n"));
    }

    private long count(String table) {
        return ((Number) database.scalar("select count(*) from " + table)).longValue();
    }

    @Test
    void insertWritesThreeRowsInOneStatement() {
        FakeDb fake = new FakeDb();

        Factory.of(Item.QUERY.on(fake.db), () -> map("name", "n")).insert(3);

        List<FakeDb.Call> inserts = fake.calls.stream().filter(call -> call.method().equals("insert")).toList();
        assertEquals(1, inserts.size());
        assertEquals(1, fake.calls.size());
        assertTrue(inserts.get(0).bindings().size() >= 3, String.valueOf(inserts.get(0).bindings()));
    }

    @Test
    void insertSetsTimestampsAndUuidKeys() {
        Factory.of(Ticket.QUERY.on(database.db).withClock(ModelClock.fixed(NOW)), () -> map("code", "c")).insert(3);

        List<Map<String, Object>> rows = database.rows("select id, created_at, updated_at from tickets");
        assertEquals(3, rows.size());
        assertEquals(3, rows.stream().map(row -> row.get("id")).distinct().count());
        for (Map<String, Object> row : rows) {
            assertEquals(36, String.valueOf(row.get("id")).length());
            assertEquals(NOW_TEXT, row.get("created_at"));
            assertEquals(NOW_TEXT, row.get("updated_at"));
        }
    }

    @Test
    void castValuesAreEncodedOnce() {
        Factory.of(Item.QUERY.on(database.db), () -> map("name", "n", "tags", List.of("a", "b"))).insert(1);

        String stored = String.valueOf(database.scalar("select tags from items"));
        assertTrue(stored.startsWith("["), stored);
        Item reloaded = Item.QUERY.on(database.db).findOrFail(1);
        assertEquals(List.of("a", "b"), reloaded.getAttribute("tags"));
    }

    @Test
    void insertFiresNoModelEvent() {
        AtomicInteger fired = new AtomicInteger();
        Item.QUERY.events().saving(m -> {
            fired.incrementAndGet();
            return true;
        }).creating(m -> {
            fired.incrementAndGet();
            return true;
        }).created(m -> fired.incrementAndGet()).saved(m -> fired.incrementAndGet());

        items().insert(2);

        assertEquals(2L, count("items"));
        assertEquals(0, fired.get());
    }

    @Test
    void insertRunsNoAfterMakingOrAfterCreatingHook() {
        List<String> calls = new ArrayList<>();

        items().afterMaking(m -> calls.add("making")).afterCreating(m -> calls.add("creating")).insert(2);

        assertEquals(2L, count("items"));
        assertTrue(calls.isEmpty(), calls.toString());
    }

    @Test
    void insertReturnsNothing() throws NoSuchMethodException {
        assertEquals(void.class, Factory.class.getMethod("insert").getReturnType());
        assertEquals(void.class, Factory.class.getMethod("insert", int.class).getReturnType());
    }

    @Test
    void insertZeroSendsNoStatementAndDoesNotThrow() {
        FakeDb fake = new FakeDb();

        Factory.of(Item.QUERY.on(fake.db), () -> map("name", "n")).insert(0);

        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void insertWithNegativeCountSendsNoStatement() {
        FakeDb fake = new FakeDb();

        Factory.of(Item.QUERY.on(fake.db), () -> map("name", "n")).insert(-2);

        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void aHiddenAttributeIsStored() {
        Factory.of(Item.QUERY.on(database.db), () -> map("name", "n", "secret", "hush")).insert(1);

        assertEquals("hush", database.scalar("select secret from items"));
    }

    @Test
    void anArrayCastModelIsStoredAsEncodedJson() {
        Factory.of(Item.QUERY.on(database.db), () -> map("name", "n", "options", Map.of("k", "v"))).insert(1);

        String stored = String.valueOf(database.scalar("select options from items"));
        assertTrue(stored.startsWith("{"), stored);
        assertTrue(stored.contains("\"k\""), stored);
        assertNotEquals("{k=v}", stored);
    }

    @Test
    void rowsWithDifferentKeySetsThrowIllegalArgumentFromFillAndInsert() {
        Factory<Item> uneven = items().sequence(map("name", "a"), map("tags", List.of("x")));

        assertThrows(IllegalArgumentException.class, () -> uneven.insert(2));

        assertEquals(0L, count("items"));
    }

    @Test
    void insertWithNoCountInsertsOneRowOrTheCount() {
        items().insert();
        assertEquals(1L, count("items"));

        items().count(3).insert();
        assertEquals(4L, count("items"));
    }
}

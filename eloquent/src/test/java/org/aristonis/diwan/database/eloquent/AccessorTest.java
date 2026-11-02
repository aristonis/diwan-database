package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.casts.Attribute;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.CastColumns;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.Test;

/** Accessors and mutators: precedence over casts, many-column writes and the per-instance cache. */
class AccessorTest {

    /** A mutable value object, compared by identity on purpose. */
    static final class Address {
        final String line1;
        final String city;

        Address(String line1, String city) {
            this.line1 = line1;
            this.city = city;
        }
    }

    /** A counter whose cached label is computed from the votes column. */
    static final class Counter extends Model {
        static final ModelQuery<Counter> QUERY = ModelQuery.of(Counter.class, Counter::new);

        @Override
        protected Map<String, Attribute<?>> accessors() {
            return Map.of("label", Attribute.<String>get(
                    (model, value, attributes) -> "votes:" + attributes.get("votes")).shouldCache());
        }
    }

    /** A model with every kind of accessor the tests need. */
    static final class Customer extends Model {
        static final ModelQuery<Customer> QUERY = ModelQuery.of(Customer.class, Customer::new);
        static final AtomicInteger RESOLVE_CALLS = new AtomicInteger();
        static final AtomicInteger SHOUT_CALLS = new AtomicInteger();

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("score", Casts.integer());
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected Map<String, Attribute<?>> accessors() {
            RESOLVE_CALLS.incrementAndGet();
            return Map.of(
                    "score", Attribute.get((model, value, attributes) -> "score:" + value),
                    "address", Attribute.<Address>of(
                            (model, value, attributes) ->
                                    new Address((String) attributes.get("line1"), (String) attributes.get("city")),
                            (model, value, attributes) ->
                                    CastColumns.of(Map.of("line1", value.line1, "city", value.city))),
                    "fresh_address", Attribute.<Address>get(
                            (model, value, attributes) -> new Address((String) attributes.get("line1"), null))
                            .withoutObjectCaching(),
                    "shout", Attribute.<String>get((model, value, attributes) -> {
                        SHOUT_CALLS.incrementAndGet();
                        return ((String) attributes.get("line1")).toUpperCase(java.util.Locale.ROOT);
                    }).shouldCache(),
                    "name", Attribute.<String>of(
                            (model, value, attributes) -> value == null ? null : "Mx " + value,
                            (model, value, attributes) -> value.trim()));
        }
    }

    @Test
    void accessorBeatsCast() {
        Customer customer = new Customer();
        customer.setRawAttributes(row("score", "7"), true);

        assertEquals("score:7", customer.getAttribute("score"));
    }

    @Test
    void mutatorWritesManyColumns() {
        Customer customer = new Customer();

        customer.setAttribute("address", new Address("1 Main St", "Rome"));

        assertEquals("1 Main St", customer.getRawAttribute("line1"));
        assertEquals("Rome", customer.getRawAttribute("city"));
        assertFalse(customer.hasAttribute("address"));
    }

    @Test
    void mutatorSkipsCastAndStoresItsResult() {
        Customer customer = new Customer();

        customer.setAttribute("name", "  Ann  ");

        assertEquals("Ann", customer.getRawAttribute("name"));
        assertEquals("Mx Ann", customer.getAttribute("name"));
    }

    @Test
    void computedAccessorSeesNewColumns() {
        Customer customer = new Customer();
        customer.setRawAttributes(row("line1", "old", "city", "Rome"), true);
        Address first = (Address) customer.getAttribute("address");
        assertSame(first, customer.getAttribute("address"));

        customer.setAttribute("line1", "new");

        assertEquals("new", ((Address) customer.getAttribute("address")).line1);
    }

    @Test
    void objectCachingCanBeOff() {
        Customer customer = new Customer();
        customer.setRawAttributes(row("line1", "a"), true);

        assertNotSame(customer.getAttribute("fresh_address"), customer.getAttribute("fresh_address"));
    }

    @Test
    void shouldCacheCachesAnyResult() {
        Customer customer = new Customer();
        customer.setRawAttributes(row("line1", "a"), true);
        int before = Customer.SHOUT_CALLS.get();

        assertEquals("A", customer.getAttribute("shout"));
        assertEquals("A", customer.getAttribute("shout"));

        assertEquals(before + 1, Customer.SHOUT_CALLS.get());
    }

    @Test
    void incrementClearsAccessorCache() {
        Customer customer = new Customer();
        customer.setRawAttributes(row("line1", "a"), true);
        assertEquals("A", customer.getAttribute("shout"));

        customer.putRawAttribute("line1", "b");

        assertEquals("B", customer.getAttribute("shout"));
    }

    // Through the real Model.increment.
    @Test
    void realIncrementClearsAccessorCache() {
        FakeDb fake = new FakeDb();
        Counter counter = Counter.QUERY.on(fake.db)
                .hydrate(Collections.singletonList(row("id", 1L, "votes", 4L))).get(0);
        assertEquals("votes:4", counter.getAttribute("label"));

        counter.increment("votes");

        assertEquals("votes:5", counter.getAttribute("label"));
    }

    @Test
    void replacingAttributesClearsAccessorCache() {
        Customer customer = new Customer();
        customer.setRawAttributes(row("line1", "a"), true);
        assertEquals("A", customer.getAttribute("shout"));

        customer.setRawAttributes(row("line1", "c"));

        assertEquals("C", customer.getAttribute("shout"));
    }

    @Test
    void resolvedOncePerClass() {
        new Customer().getAttribute("shout_missing");
        int after = Customer.RESOLVE_CALLS.get();

        Customer other = new Customer();
        other.getAttribute("score");
        other.setAttribute("name", "Bo");

        assertEquals(1, after);
        assertEquals(1, Customer.RESOLVE_CALLS.get());
    }

    @Test
    void computedAccessorWithoutColumn() {
        Customer customer = new Customer();
        customer.setRawAttributes(row("line1", "x", "city", "Oslo"), true);

        Address address = (Address) customer.getAttribute("address");

        assertFalse(customer.hasAttribute("address"));
        assertEquals("x", address.line1);
        assertEquals("Oslo", address.city);
    }

    @Test
    void getOriginalGoesThroughAccessorUncached() {
        Customer customer = new Customer();
        customer.setRawAttributes(row("name", "Ann", "line1", "a"), true);
        customer.setAttribute("name", "Bea");

        assertEquals("Mx Ann", customer.getOriginal("name"));
        assertEquals("Ann", customer.getRawOriginal("name"));
        assertEquals("Mx Bea", customer.getAttribute("name"));
        assertTrue(customer.getOriginal("address") instanceof Address);
        assertNotSame(customer.getOriginal("address"), customer.getOriginal("address"));
    }

    @Test
    void keyWithoutAccessorFallsBackToCastAndRaw() {
        Customer customer = new Customer();

        customer.setAttribute("city", "Rome");

        assertEquals("Rome", customer.getAttribute("city"));
    }

    // A cached accessor object is never written back on save.
    @Test
    void saveDoesNotWriteStaleObjectBack() throws SQLException {
        try (java.sql.Connection jdbc = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Connection connection = Connection.builder().name("sqlite")
                    .connectionProvider(new SQLiteModelTest.SingleConnection(jdbc)).build();
            SQLiteSchema.of(connection).create("customers", t -> {
                t.id();
                t.string("line1");
                t.string("city");
                t.timestamps();
            });
            ModelQuery<Customer> customers = Customer.QUERY.on(SQLiteQuery.of(connection));
            Customer customer = customers.create(row("line1", "old", "city", "Rome"));
            Address stale = (Address) customer.getAttribute("address");

            customer.setAttribute("line1", "new");
            assertTrue(customer.save());

            assertEquals("old", stale.line1);
            Customer reloaded = customers.findOrFail(customer.getKey());
            assertEquals("new", reloaded.getRawAttribute("line1"));
            assertEquals("new", ((Address) reloaded.getAttribute("address")).line1);
        }
    }
}

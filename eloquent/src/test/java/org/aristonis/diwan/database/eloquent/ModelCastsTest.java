package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.junit.jupiter.api.Test;

/** Model-level halves of the primitive cast scenarios. */
class ModelCastsTest {

    /** An order with a decimal total and an int cast on a key that is not a column. */
    static final class Order extends Model {
        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("total", Casts.of("decimal:2"), "score", Casts.integer());
        }
    }

    /** A counter whose votes read as text, so a cast-aware increment would differ from a raw one. */
    static final class Counter extends Model {
        static final ModelQuery<Counter> QUERY = ModelQuery.of(Counter.class, Counter::new);

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("votes", Casts.string());
        }
    }

    @Test
    void originalCastVsRaw() {
        Order order = new Order();
        order.setRawAttributes(row("total", "12.5"), true);

        assertEquals(new BigDecimal("12.50"), order.getOriginal("total"));
        assertEquals("12.5", order.getRawOriginal("total"));
        assertEquals(new BigDecimal("12.50"), order.getOriginal().get("total"));
    }

    @Test
    void castOnUnknownKeyApplies() {
        Order order = new Order();

        order.setAttribute("score", "3");

        assertEquals(3L, order.getAttributes().get("score"));
        assertEquals(3L, order.getAttribute("score"));
    }

    @Test
    void uncastKeysKeepSprint18Behaviour() {
        Order order = new Order();
        order.setRawAttributes(row("name", 1), true);

        order.setAttribute("name", "1");

        assertFalse(order.isDirty("name"));
        assertEquals("1", order.getAttribute("name"));
    }

    @Test
    void decimalRewriteWithSameValueIsClean() {
        Order order = new Order();
        order.setRawAttributes(row("total", "12.50"), true);

        order.setAttribute("total", new BigDecimal("12.5"));

        assertFalse(order.isDirty("total"));
        order.setAttribute("total", "12.51");
        assertTrue(order.isDirty("total"));
    }

    @Test
    void incrementUsesRaw() {
        FakeDb fake = new FakeDb();
        Counter counter = Counter.QUERY.on(fake.db)
                .hydrate(Collections.singletonList(row("id", 1L, "votes", 4L))).get(0);

        counter.increment("votes", 2);

        assertEquals(6L, counter.getAttributes().get("votes"));
        assertEquals("6", counter.getAttribute("votes"));
        assertTrue(counter.isClean());
        assertEquals(1L, fake.last().bindings().get(fake.last().bindings().size() - 1));
    }

    @Test
    void typedGettersSeeCasts() {
        Order order = new Order();
        order.setRawAttributes(row("total", "12.5", "score", "3"));

        assertEquals("12.50", order.getString("total"));
        assertEquals(new BigDecimal("12.50"), order.getBigDecimal("total"));
        assertEquals(3L, order.getLong("score"));
        assertEquals(12.5, order.getDouble("total"));
    }
}

package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aristonis.diwan.database.eloquent.casts.Attribute;
import org.aristonis.diwan.database.eloquent.casts.BackedValue;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.aristonis.diwan.database.eloquent.exception.JsonException;
import org.junit.jupiter.api.Test;

/** The map and JSON forms: hidden, visible and appended keys, and the serialized value forms. */
class ModelSerializationTest {

    /** A status stored as a number. */
    enum Status implements BackedValue {
        OPEN(1L), PAID(2L);

        private final Long value;

        Status(Long value) {
            this.value = value;
        }

        @Override
        public Object value() {
            return value;
        }
    }

    /** An invoice with a cast of every serialized form and one computed accessor. */
    static class Invoice extends Model {
        static final ModelQuery<Invoice> QUERY = ModelQuery.of(Invoice.class, Invoice::new);

        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("meta", Casts.json());
            casts.put("total", Casts.of("decimal:2"));
            casts.put("status", Casts.enumOf(Status.class));
            casts.put("paid_at", Casts.datetime());
            casts.put("due", Casts.date());
            casts.put("shown_on", Casts.datetime("yyyy-MM-dd"));
            casts.put("stamp", Casts.timestamp());
            return casts;
        }

        @Override
        protected List<String> fillable() {
            return Arrays.asList("meta", "total", "first", "last");
        }

        @Override
        protected Map<String, Attribute<?>> accessors() {
            return Map.of("full_name", Attribute.<String>get(
                    (model, value, attributes) -> attributes.get("first") + " " + attributes.get("last")));
        }
    }

    /** An invoice whose configuration hides, shows and appends keys. */
    static final class ConfiguredInvoice extends Invoice {
        @Override
        protected List<String> hidden() {
            return Collections.singletonList("secret");
        }

        @Override
        protected List<String> appends() {
            return Collections.singletonList("full_name");
        }
    }

    /** A model with an appended key that has no accessor, and no timestamps. */
    static final class Broken extends Model {
        @Override
        protected List<String> appends() {
            return Collections.singletonList("missing");
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    private final FakeDb fake = new FakeDb();

    private Invoice stored(Map<String, Object> row) {
        return Invoice.QUERY.on(fake.db).hydrate(Collections.singletonList(row)).get(0);
    }

    @Test
    void fillAppliesCasts() {
        Invoice invoice = new Invoice();

        invoice.fill(row("meta", Map.of("a", 1L), "first", "Ann"));

        assertEquals("{\"a\":1}", invoice.getAttributes().get("meta"));
        assertEquals(Map.of("a", 1L), invoice.toMap().get("meta"));
        assertFalse(invoice.hasAttribute("secret"));
    }

    @Test
    void visibleThenHidden() {
        Invoice invoice = new Invoice();
        invoice.setRawAttributes(row("first", "Ann", "last", "Lee", "secret", "x"));

        invoice.setVisible(Arrays.asList("first", "secret")).setHidden(Collections.singletonList("secret"));

        assertEquals(row("first", "Ann"), invoice.toMap());
    }

    @Test
    void makeVisible() {
        Invoice open = new Invoice();
        open.setRawAttributes(row("first", "Ann", "secret", "x"));
        open.makeHidden("secret", "first");

        open.makeVisible("secret");

        assertEquals(Collections.singletonList("first"), open.getHidden());
        assertTrue(open.getVisible().isEmpty());
        assertEquals(row("secret", "x"), open.toMap());

        Invoice narrow = new Invoice();
        narrow.setRawAttributes(row("first", "Ann", "secret", "x"));
        narrow.setVisible(Collections.singletonList("first")).makeHidden("secret");

        narrow.makeVisible("secret");

        assertEquals(Arrays.asList("first", "secret"), narrow.getVisible());
        assertTrue(narrow.getHidden().isEmpty());
        assertEquals(row("first", "Ann", "secret", "x"), narrow.toMap());
    }

    @Test
    void appendWithoutAccessorFails() {
        Broken broken = new Broken();

        IllegalStateException e = assertThrows(IllegalStateException.class, broken::toMap);

        assertTrue(e.getMessage().contains("[missing]"), e.getMessage());
    }

    @Test
    void hiddenAppendIsDropped() {
        Invoice invoice = new Invoice();
        invoice.setRawAttributes(row("first", "Ann", "last", "Lee"));
        invoice.append("full_name");
        assertEquals("Ann Lee", invoice.toMap().get("full_name"));

        invoice.makeHidden("full_name");

        assertFalse(invoice.toMap().containsKey("full_name"));
    }

    @Test
    void listsAreCopiedFromConfigPerInstance() {
        ConfiguredInvoice first = new ConfiguredInvoice();
        ConfiguredInvoice second = new ConfiguredInvoice();
        first.setRawAttributes(row("first", "Ann", "last", "Lee", "secret", "x"));

        first.withoutAppends().makeVisible("secret");

        assertEquals(row("first", "Ann", "last", "Lee", "secret", "x"), first.toMap());
        assertEquals(Collections.singletonList("full_name"), second.getAppends());
        assertEquals(Collections.singletonList("secret"), second.getHidden());
        assertThrows(UnsupportedOperationException.class, () -> second.getHidden().add("y"));
        second.setAppends(Collections.emptyList()).append("full_name", "full_name");
        assertEquals(Collections.singletonList("full_name"), second.getAppends());
    }

    @Test
    void dateForms() {
        Invoice invoice = new Invoice();
        invoice.setRawAttributes(row(
                "paid_at", "2026-11-02 10:15:30",
                "due", "2026-11-30",
                "shown_on", "2026-11-02 10:15:30",
                "stamp", "2026-11-02 10:15:30"));

        Map<String, Object> map = invoice.toMap();

        assertEquals("2026-11-02T10:15:30.000000Z", map.get("paid_at"));
        assertEquals("2026-11-30", map.get("due"));
        assertEquals("2026-11-02", map.get("shown_on"));
        assertEquals(Instant.parse("2026-11-02T10:15:30Z").getEpochSecond(), map.get("stamp"));
    }

    @Test
    void castForms() {
        Invoice invoice = new Invoice();
        invoice.setRawAttributes(row("total", "12.5", "status", 2L, "meta", "{\"a\":{\"b\":[1,2]}}"));

        Map<String, Object> map = invoice.toMap();

        assertEquals("12.50", map.get("total"));
        assertEquals(2L, map.get("status"));
        assertEquals(Map.of("a", Map.of("b", Arrays.asList(1L, 2L))), map.get("meta"));
        assertEquals("{\"total\":\"12.50\",\"status\":2,\"meta\":{\"a\":{\"b\":[1,2]}}}", invoice.toJson());
    }

    @Test
    void nullCastValuesStayNull() {
        Invoice invoice = new Invoice();
        invoice.setRawAttributes(row("total", null, "paid_at", null));

        assertEquals(row("total", null, "paid_at", null), invoice.toMap());
    }

    @Test
    void implicitTimestampsSerializeAsDatetime() {
        Invoice invoice = new Invoice();
        invoice.setRawAttributes(row("created_at", "2026-11-02 10:15:30", "updated_at", Instant.parse(
                "2026-11-02T10:15:30.123456Z")), true);

        Map<String, Object> map = invoice.toMap();

        assertEquals("2026-11-02T10:15:30.000000Z", map.get("created_at"));
        assertEquals("2026-11-02T10:15:30.123456Z", map.get("updated_at"));
        assertEquals("2026-11-02 10:15:30", invoice.getAttribute("created_at"));
        assertFalse(invoice.isDirty());
    }

    @Test
    void implicitTimestampThatIsNotAMomentFails() {
        Invoice invoice = new Invoice();
        invoice.setRawAttributes(row("created_at", "soon"));

        InvalidCastException e = assertThrows(InvalidCastException.class, invoice::toMap);

        assertFalse(e.getMessage().contains("soon"), e.getMessage());
    }

    @Test
    void noTimestampsMeansNoImplicitDates() {
        Broken model = new Broken();
        model.setRawAttributes(row("created_at", "2026-11-02 10:15:30"));
        model.withoutAppends();

        assertEquals(row("created_at", "2026-11-02 10:15:30"), model.toMap());
    }

    @Test
    void nanBreaksToJson() {
        Invoice invoice = new Invoice();
        invoice.setRawAttributes(row("ratio", Double.NaN));

        assertTrue(Double.isNaN((Double) invoice.toMap().get("ratio")));
        assertThrows(JsonException.class, invoice::toJson);
    }

    @Test
    void bytesBreakToJson() {
        Invoice invoice = new Invoice();
        invoice.setRawAttributes(row("blob", new byte[] {1, 2}));

        assertThrows(JsonException.class, invoice::toJson);
    }

    @Test
    void toMapCopiesNestedValues() {
        Invoice invoice = new Invoice();
        invoice.setRawAttributes(row("meta", "{\"a\":1}"), true);

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) invoice.toMap().get("meta");
        meta.put("b", 2L);

        assertEquals(Map.of("a", 1L), invoice.getAttribute("meta"));
        assertFalse(invoice.isDirty());
    }

    @Test
    void replicateKeepsCasts() {
        Invoice invoice = stored(row("id", 1L, "total", "12.5", "meta", "{\"a\":1}"));

        Invoice copy = invoice.replicate();

        assertNotSame(invoice, copy);
        assertEquals(row("total", "12.5", "meta", "{\"a\":1}"), copy.getAttributes());
        assertEquals(new BigDecimal("12.50"), copy.getAttribute("total"));
        assertEquals(Map.of("a", 1L), copy.getAttribute("meta"));

        fake.returns("select * from \"invoices\" where \"id\" = ? limit 1", row("id", 1L, "total", "7.1"));
        Optional<Invoice> fresh = invoice.fresh();

        assertTrue(fresh.isPresent());
        assertEquals("7.1", fresh.get().getAttributes().get("total"));
        assertEquals(new BigDecimal("7.10"), fresh.get().getAttribute("total"));
        assertEquals("7.10", fresh.get().toMap().get("total"));
    }
}

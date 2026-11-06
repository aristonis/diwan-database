package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.CastColumns;
import org.aristonis.diwan.database.eloquent.casts.CastComparator;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.casts.InvalidCastException;
import org.aristonis.diwan.database.eloquent.json.JsonCodec;
import org.junit.jupiter.api.Test;

/** Class casts and the per-instance object cache of the cast layer. */
class ClassCastTest {

    private static final String SPACED = "{\"a\": 1}";
    private static final String SECRET = "secret-value-that-must-not-leak";

    /** A plain value object for the two-column price cast. */
    static final class Price {
        final BigDecimal amount;
        final String currency;

        Price(BigDecimal amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }
    }

    /** Stores {@link Price} in {@code amount} and {@code currency}. */
    static final class PriceCast implements Cast<Price> {
        @Override
        public Price get(Model model, String key, Object raw, Map<String, Object> attributes) {
            return new Price(new BigDecimal(attributes.get("amount").toString()),
                    (String) attributes.get("currency"));
        }

        @Override
        public Object set(Model model, String key, Price value, Map<String, Object> attributes) {
            Map<String, Object> columns = new LinkedHashMap<>();
            columns.put("amount", value.amount.toPlainString());
            columns.put("currency", value.currency);
            return CastColumns.of(columns);
        }
    }

    /** Decodes JSON into a map and re-encodes it compactly; no comparator. */
    static final class CompactJsonCast implements Cast<Object> {
        @Override
        public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
            return JsonCodec.standard().decode(raw.toString());
        }

        @Override
        public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
            return JsonCodec.standard().encode(value);
        }
    }

    /** Text cast whose comparator says everything is equivalent. */
    static final class AlwaysSameCast implements Cast<String>, CastComparator {
        @Override
        public String get(Model model, String key, Object raw, Map<String, Object> attributes) {
            return raw.toString();
        }

        @Override
        public Object set(Model model, String key, String value, Map<String, Object> attributes) {
            return value;
        }

        @Override
        public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
            return true;
        }
    }

    /** Reads every non-null value as null. */
    static final class NullCast implements Cast<Object> {
        @Override
        public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
            return null;
        }

        @Override
        public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
            return value;
        }
    }

    /** Fails on every read with a message carrying the stored value. */
    static final class FailingCast implements Cast<Object> {
        @Override
        public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
            throw new IllegalStateException("cannot read " + raw);
        }

        @Override
        public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
            return value;
        }
    }

    /** A model using every cast above plus a built-in json cast. */
    static final class Wallet extends Model {
        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("meta", Casts.json());
            casts.put("price", new PriceCast());
            casts.put("doc", new CompactJsonCast());
            casts.put("label", new AlwaysSameCast());
            casts.put("ghost", new NullCast());
            casts.put("broken", new FailingCast());
            casts.put("total", Casts.of("decimal:2"));
            casts.put("happened_at", Casts.of("immutable_datetime"));
            return casts;
        }
    }

    /** A model with an invalid cast string. */
    static final class BadSpec extends Model {
        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("total", Casts.of("nope:3"));
        }
    }

    private static Wallet stored(Object... pairs) {
        Wallet wallet = new Wallet();
        wallet.setRawAttributes(row(pairs), true);
        return wallet;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metaOf(Model model) {
        return (Map<String, Object>) model.getAttribute("meta");
    }

    @Test
    void readingJsonDoesNotDirty() {
        Wallet wallet = stored("meta", SPACED);

        assertEquals(Map.of("a", 1L), wallet.getAttribute("meta"));

        assertFalse(wallet.isDirty());
        assertEquals(SPACED, wallet.getAttributes().get("meta"));
    }

    @Test
    void mutatingReadMapMarksDirty() {
        Wallet wallet = stored("meta", SPACED);

        metaOf(wallet).put("b", 2L);

        assertTrue(wallet.isDirty("meta"));
        assertEquals("{\"a\":1,\"b\":2}", wallet.getAttributes().get("meta"));
        assertSame(metaOf(wallet), metaOf(wallet));
    }

    @Test
    void multiColumnSet() {
        Wallet wallet = stored("amount", "1.00", "currency", "USD");

        wallet.setAttribute("price", new Price(new BigDecimal("2.50"), "EUR"));

        assertEquals("2.50", wallet.getAttributes().get("amount"));
        assertEquals("EUR", wallet.getAttributes().get("currency"));
        assertTrue(wallet.isDirty("amount"));
        assertTrue(wallet.isDirty("currency"));
    }

    @Test
    void normalisingSetDoesNotDirtyOnRead() {
        Wallet wallet = stored("doc", SPACED);

        wallet.getAttribute("doc");

        assertFalse(wallet.isDirty());
        assertEquals(SPACED, wallet.getAttributes().get("doc"));
    }

    @Test
    void comparatorWins() {
        Wallet wallet = stored("label", "old");

        wallet.setAttribute("label", "new");

        assertFalse(wallet.isDirty("label"));
        assertEquals("new", wallet.getAttribute("label"));
    }

    @Test
    void cacheClearedOnRawReplace() {
        Wallet wallet = stored("meta", SPACED);
        metaOf(wallet);

        wallet.setRawAttributes(row("meta", "{\"c\":3}"));
        assertEquals(Map.of("c", 3L), wallet.getAttribute("meta"));

        wallet.discardChanges();
        assertEquals(Map.of("a", 1L), wallet.getAttribute("meta"));
    }

    @Test
    void restoreTracksTheSnapshotObjectsAgain() {
        Wallet wallet = stored("meta", SPACED);
        Map<String, Object> first = metaOf(wallet);
        ModelAttributes.Snapshot before = wallet.snapshotAttributes();
        Map<String, Object> other = new LinkedHashMap<>(Map.of("z", 9L));
        wallet.setAttribute("meta", other);

        wallet.restoreAttributes(before);

        Map<String, Object> restored = metaOf(wallet);
        assertEquals(Map.of("a", 1L), restored);
        assertNotSame(other, restored);
        // Review fix 3: the caller may still hold the snapshot's object, so it stays tracked.
        assertSame(first, restored);
    }

    @Test
    void getMayReturnNull() {
        Wallet wallet = stored("ghost", "something");

        assertNull(wallet.getAttribute("ghost"));
        assertFalse(wallet.isDirty());
    }

    @Test
    void errorsAreWrapped() {
        Wallet wallet = stored("broken", SECRET, "label", "x");

        InvalidCastException read = assertThrows(InvalidCastException.class,
                () -> wallet.getAttribute("broken"));
        InvalidCastException write = assertThrows(InvalidCastException.class,
                () -> wallet.setAttribute("label", 42L));

        assertEquals("broken", read.getKey());
        assertEquals(Wallet.class, read.getModelClass());
        assertFalse(read.getMessage().contains(SECRET));
        assertEquals("label", write.getKey());
        assertTrue(write.getCause() instanceof ClassCastException);
    }

    @Test
    void invalidSpecIsWrappedWithModelAndKey() {
        BadSpec model = new BadSpec();

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> model.getAttribute("total"));

        assertEquals(BadSpec.class, e.getModelClass());
        assertEquals("total", e.getKey());
        assertEquals("nope:3", e.getCastName());
    }

    @Test
    void syncOriginalAttributeMerges() {
        Wallet wallet = stored("meta", SPACED);
        metaOf(wallet).put("b", 2L);

        wallet.syncOriginalAttribute("meta");

        assertEquals("{\"a\":1,\"b\":2}", wallet.getRawOriginal("meta"));
        assertFalse(wallet.isDirty("meta"));
    }

    @Test
    void hasOriginalMerges() {
        Wallet wallet = stored("meta", SPACED);
        metaOf(wallet).put("b", 2L);

        assertTrue(wallet.hasRawOriginal("meta"));
        assertEquals(SPACED, wallet.getRawOriginal("meta"));
        assertEquals("{\"a\":1,\"b\":2}", wallet.getAttributes().get("meta"));
        assertEquals(Map.of("a", 1L), wallet.getOriginal("meta"));
    }

    @Test
    void reservedKeysUntouched() {
        Wallet wallet = stored("id", "7", "created_at", "2026-11-02 10:15:30", "meta", SPACED);

        assertEquals("7", wallet.getAttribute("id"));
        assertEquals("7", wallet.getKey());
        assertEquals("2026-11-02 10:15:30", wallet.getAttribute("created_at"));
        assertFalse(wallet.hasCast("id"));
        assertFalse(wallet.getCasts().containsKey("created_at"));
    }

    @Test
    void hasCastNormalisesAliases() {
        Wallet wallet = new Wallet();

        assertTrue(wallet.hasCast("meta"));
        assertTrue(wallet.hasCast("meta", "array"));
        assertTrue(wallet.hasCast("meta", "JSON"));
        assertTrue(wallet.hasCast("total", "decimal:4"));
        assertTrue(wallet.hasCast("happened_at", "custom_datetime"));
        assertTrue(wallet.hasCast("price", PriceCast.class.getName()));
        assertFalse(wallet.hasCast("meta", "int"));
        assertFalse(wallet.hasCast("missing"));
    }

    @Test
    void castsResolveOncePerClass() {
        assertSame(new Wallet().getCasts(), new Wallet().getCasts());
    }
}

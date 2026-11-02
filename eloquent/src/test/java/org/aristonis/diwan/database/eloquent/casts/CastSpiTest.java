package org.aristonis.diwan.database.eloquent.casts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.junit.jupiter.api.Test;

class CastSpiTest {

    @Test
    void castColumnsCopiesInput() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("amount", 10L);

        CastColumns columns = CastColumns.of(source);
        source.put("currency", "USD");

        assertEquals(1, columns.columns().size());
        assertEquals(10L, columns.columns().get("amount"));
    }

    @Test
    void castColumnsIsUnmodifiable() {
        Map<String, Object> source = new HashMap<>();
        source.put("amount", 10L);
        CastColumns columns = CastColumns.of(source);

        assertThrows(UnsupportedOperationException.class, () -> columns.columns().put("x", 1));
    }

    @Test
    void castColumnsKeepsInsertionOrder() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("z", 1);
        source.put("a", 2);
        source.put("m", 3);

        CastColumns columns = CastColumns.of(source);

        assertEquals(Arrays.asList("z", "a", "m"), new ArrayList<>(columns.columns().keySet()));
    }

    @Test
    void castColumnsAllowsNullValues() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("amount", null);

        CastColumns columns = CastColumns.of(source);

        assertTrue(columns.columns().containsKey("amount"));
        assertNull(columns.columns().get("amount"));
    }

    @Test
    void castColumnsRejectsNullMap() {
        assertThrows(NullPointerException.class, () -> CastColumns.of(null));
    }

    @Test
    void castColumnsRejectsEmptyMap() {
        assertThrows(IllegalArgumentException.class, () -> CastColumns.of(new HashMap<String, Object>()));
    }

    @Test
    void castColumnsRejectsNullKey() {
        Map<String, Object> source = new HashMap<>();
        source.put(null, 1);

        assertThrows(IllegalArgumentException.class, () -> CastColumns.of(source));
    }

    @Test
    void castColumnsRejectsEmptyKey() {
        Map<String, Object> source = new HashMap<>();
        source.put("", 1);

        assertThrows(IllegalArgumentException.class, () -> CastColumns.of(source));
    }

    @Test
    void castDoesNotHandleNullByDefault() {
        Cast<String> cast = new Cast<String>() {
            @Override
            public String get(Model model, String key, Object raw, Map<String, Object> attributes) {
                return String.valueOf(raw);
            }

            @Override
            public Object set(Model model, String key, String value, Map<String, Object> attributes) {
                return value;
            }
        };

        assertFalse(cast.handlesNull());
    }

    @Test
    void invalidCastExceptionCarriesFields() {
        InvalidCastException e = new InvalidCastException(String.class, "price", "decimal:2");

        assertSame(String.class, e.getModelClass());
        assertEquals("price", e.getKey());
        assertEquals("decimal:2", e.getCastName());
        assertNull(e.getCause());
        assertTrue(e instanceof IllegalArgumentException);
    }

    @Test
    void invalidCastExceptionMessageNamesTypesNotValues() {
        RuntimeException cause = new RuntimeException("secret-value-123");

        InvalidCastException e = new InvalidCastException(String.class, "price", "decimal:2", cause);

        assertSame(cause, e.getCause());
        assertTrue(e.getMessage().contains("price"));
        assertTrue(e.getMessage().contains("decimal:2"));
        assertTrue(e.getMessage().contains(String.class.getName()));
        assertTrue(e.getMessage().contains(RuntimeException.class.getName()));
        assertFalse(e.getMessage().contains("secret-value-123"));
    }

    @Test
    void invalidCastExceptionAcceptsUnknownModel() {
        InvalidCastException e = new InvalidCastException(null, "price", "decimal:2");

        assertNull(e.getModelClass());
        assertTrue(e.getMessage().contains("price"));
    }

    @Test
    void backedValueReturnsValue() {
        BackedValue value = () -> "A";

        assertEquals("A", value.value());
    }
}

package org.aristonis.diwan.database.eloquent.casts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AttributeTest {

    private static final Attribute.Getter<String> UPPER =
            (model, value, attributes) -> value == null ? null : value.toString().toUpperCase();
    private static final Attribute.Setter<String> LOWER =
            (model, value, attributes) -> value == null ? null : value.toLowerCase();

    @Test
    void ofKeepsBothFunctionsAndDefaultFlags() {
        Attribute<String> attribute = Attribute.of(UPPER, LOWER);

        assertSame(UPPER, attribute.getter());
        assertSame(LOWER, attribute.setter());
        assertFalse(attribute.isCaching());
        assertTrue(attribute.isObjectCaching());
    }

    @Test
    void ofAcceptsNullFunctionsLikeLaravelMake() {
        Attribute<String> attribute = Attribute.of(null, null);

        assertNull(attribute.getter());
        assertNull(attribute.setter());
    }

    @Test
    void getFactoryHasNoSetter() {
        Attribute<String> attribute = Attribute.get(UPPER);

        assertSame(UPPER, attribute.getter());
        assertNull(attribute.setter());
    }

    @Test
    void setFactoryHasNoGetter() {
        Attribute<String> attribute = Attribute.set(LOWER);

        assertNull(attribute.getter());
        assertSame(LOWER, attribute.setter());
    }

    @Test
    void getAndSetFactoriesRejectNull() {
        assertThrows(NullPointerException.class, () -> Attribute.get(null));
        assertThrows(NullPointerException.class, () -> Attribute.set(null));
    }

    @Test
    void shouldCacheReturnsNewCopyAndLeavesOriginalUnchanged() {
        Attribute<String> original = Attribute.of(UPPER, LOWER);

        Attribute<String> cached = original.shouldCache();

        assertNotSame(original, cached);
        assertTrue(cached.isCaching());
        assertFalse(original.isCaching());
        assertSame(UPPER, cached.getter());
        assertSame(LOWER, cached.setter());
        assertTrue(cached.isObjectCaching());
    }

    @Test
    void withoutObjectCachingReturnsNewCopyAndLeavesOriginalUnchanged() {
        Attribute<String> original = Attribute.of(UPPER, LOWER).shouldCache();

        Attribute<String> plain = original.withoutObjectCaching();

        assertNotSame(original, plain);
        assertFalse(plain.isObjectCaching());
        assertTrue(original.isObjectCaching());
        assertTrue(plain.isCaching());
        assertSame(UPPER, plain.getter());
        assertSame(LOWER, plain.setter());
    }

    @Test
    void functionsRunThroughTheirInterfaces() {
        Attribute<String> attribute = Attribute.of(UPPER, LOWER);

        assertEquals("ADA", attribute.getter().get(null, "ada", Map.of()));
        assertEquals("ada", attribute.setter().set(null, "ADA", Map.of()));
    }

    @Test
    void setterMayReturnCastColumns() {
        Attribute.Setter<String> split =
                (model, value, attributes) -> CastColumns.of(Map.of("first", value, "last", value));

        Object result = Attribute.set(split).setter().set(null, "x", Map.of());

        assertTrue(result instanceof CastColumns);
    }
}

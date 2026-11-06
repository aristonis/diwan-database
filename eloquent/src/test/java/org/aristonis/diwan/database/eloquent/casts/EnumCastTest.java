package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnumCastTest {

    private static final Map<String, Object> NO_ATTRIBUTES = Collections.emptyMap();

    enum Status { DRAFT, PUBLISHED }

    enum Priority implements BackedValue {
        LOW(1L), HIGH(2L);

        private final Long value;

        Priority(Long value) {
            this.value = value;
        }

        @Override
        public Object value() {
            return value;
        }
    }

    enum Clash implements BackedValue {
        A("x"), B("x");

        private final String value;

        Clash(String value) {
            this.value = value;
        }

        @Override
        public Object value() {
            return value;
        }
    }

    @Test
    void plainEnumStoresName() {
        EnumCast cast = new EnumCast(Status.class);

        assertSame(Status.DRAFT, cast.get(null, "status", "DRAFT", NO_ATTRIBUTES));
        assertEquals("PUBLISHED", cast.set(null, "status", Status.PUBLISHED, NO_ATTRIBUTES));
        assertEquals("DRAFT", cast.serialize(null, "status", Status.DRAFT, NO_ATTRIBUTES));
    }

    @Test
    void backedEnumStoresValue() {
        EnumCast cast = new EnumCast(Priority.class);

        assertEquals(2L, cast.set(null, "priority", Priority.HIGH, NO_ATTRIBUTES));
        assertEquals(1L, cast.serialize(null, "priority", Priority.LOW, NO_ATTRIBUTES));
    }

    @Test
    void backedLongValue() {
        EnumCast cast = new EnumCast(Priority.class);

        assertSame(Priority.HIGH, cast.get(null, "priority", "2", NO_ATTRIBUTES));
        assertSame(Priority.HIGH, cast.get(null, "priority", 2L, NO_ATTRIBUTES));
    }

    @Test
    void unknownValueThrows() {
        EnumCast cast = new EnumCast(Status.class);

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> cast.get(null, "status", "ARCHIVED", NO_ATTRIBUTES));

        assertEquals("status", e.getKey());
        assertTrue(e.getCastName().contains(Status.class.getName()));
        assertFalse(e.getCause().getMessage().contains("ARCHIVED"));
    }

    @Test
    void setAcceptsConstantOrStoredValue() {
        EnumCast cast = new EnumCast(Status.class);

        assertEquals("DRAFT", cast.set(null, "status", "DRAFT", NO_ATTRIBUTES));
        assertEquals("DRAFT", cast.set(null, "status", Status.DRAFT, NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class, () -> cast.set(null, "status", "nope", NO_ATTRIBUTES));
    }

    @Test
    void foreignEnumConstantIsRejected() {
        EnumCast cast = new EnumCast(Status.class);

        assertThrows(InvalidCastException.class, () -> cast.set(null, "status", Priority.LOW, NO_ATTRIBUTES));
    }

    @Test
    void nullPassesThrough() {
        EnumCast cast = new EnumCast(Status.class);

        assertNull(cast.get(null, "status", null, NO_ATTRIBUTES));
        assertNull(cast.set(null, "status", null, NO_ATTRIBUTES));
    }

    @Test
    void duplicateValueTextRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EnumCast(Clash.class));
    }

    @Test
    void nonEnumTypeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EnumCast(String.class));
    }

    @Test
    void compareMatchesByResolvedConstant() {
        EnumCast cast = new EnumCast(Priority.class);

        assertTrue(cast.compare(null, "priority", "2", 2L));
        assertFalse(cast.compare(null, "priority", "1", 2L));
        assertFalse(cast.compare(null, "priority", "9", "8"));
        assertFalse(cast.compare(null, "priority", null, 2L));
    }
}

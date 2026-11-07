package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aristonis.diwan.database.schema.grammar.SchemaGrammar;
import org.junit.jupiter.api.Test;

class CollationTest {

    private static final class BaseGrammar extends SchemaGrammar {
        String nameOf(Collation collation) {
            return collationName(collation);
        }
    }

    @Test
    void namedNeverEqualsPortable() {
        assertNotEquals(Collation.BINARY, Collation.of("binary"));
        assertNotEquals(Collation.BINARY, Collation.of("C"));
        assertNotEquals(Collation.of("binary"), Collation.BINARY);
        assertEquals(Collation.of("NOCASE"), Collation.of("NOCASE"));
        assertEquals(Collation.of("NOCASE").hashCode(), Collation.of("NOCASE").hashCode());
        assertTrue(Collation.BINARY.isPortable());
        assertFalse(Collation.of("NOCASE").isPortable());
        assertEquals("binary", Collation.BINARY.name());
        assertEquals("NOCASE", Collation.of("NOCASE").name());
    }

    @Test
    void badCollationNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Collation.of(""));
        assertThrows(IllegalArgumentException.class, () -> Collation.of("no'case"));
        assertThrows(IllegalArgumentException.class, () -> Collation.of("no\"case"));
        assertThrows(IllegalArgumentException.class, () -> Collation.of("no\ncase"));
        assertThrows(NullPointerException.class, () -> Collation.of(null));
    }

    @Test
    void unmappedPortableThrows() {
        BaseGrammar grammar = new BaseGrammar();

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> grammar.nameOf(Collation.BINARY));
        assertTrue(thrown.getMessage().contains("no name for the portable collation binary"));
        assertEquals("NOCASE", grammar.nameOf(Collation.of("NOCASE")));
    }

    @Test
    void rawColumnRefusesCollation() {
        Blueprint table = new Blueprint("t", "", false);
        ColumnDefinition raw = table.rawColumn("flags", "integer default 0");

        assertThrows(IllegalStateException.class, () -> raw.collation("NOCASE"));
        assertThrows(IllegalStateException.class, () -> raw.collation(Collation.BINARY));
    }
}

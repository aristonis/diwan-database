package org.aristonis.diwan.database.schema.grammar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SchemaGrammarLiteralTest {

    private static final class BaseGrammar extends SchemaGrammar {
        String literalOf(Object value) {
            return literal(value);
        }
    }

    @Test
    void textLiteralsDoubleQuotesAndKeepBackslashes() {
        assertEquals("'it''s C:\\'", new BaseGrammar().literalOf("it's C:\\"));
    }

    @Test
    void textWithANulCharacterIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new BaseGrammar().literalOf("a\0b"));
    }
}

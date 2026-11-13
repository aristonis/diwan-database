package org.aristonis.diwan.database.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class SearchOptionsTest {

    @Test
    void defaultIsCaseInsensitive() {
        assertFalse(SearchOptions.DEFAULT.isCaseSensitive());
    }

    @Test
    void caseSensitiveFactoryIsCaseSensitive() {
        assertTrue(SearchOptions.caseSensitive().isCaseSensitive());
    }

    @Test
    void withCaseSensitiveReturnsANewValueAndLeavesTheSourceUnchanged() {
        SearchOptions changed = SearchOptions.DEFAULT.withCaseSensitive(true);

        assertTrue(changed.isCaseSensitive());
        assertFalse(SearchOptions.DEFAULT.isCaseSensitive());
        assertEquals(SearchOptions.DEFAULT, changed.withCaseSensitive(false));
    }

    @Test
    void equalSettingsAreEqualAndHashEqual() {
        SearchOptions changed = SearchOptions.DEFAULT.withCaseSensitive(true);

        assertEquals(SearchOptions.caseSensitive(), changed);
        assertEquals(SearchOptions.caseSensitive().hashCode(), changed.hashCode());
        assertNotEquals(SearchOptions.DEFAULT, changed);
        assertNotEquals(null, SearchOptions.DEFAULT);
        assertNotEquals("SearchOptions", SearchOptions.DEFAULT);
    }

    @Test
    void toStringNamesTheSetting() {
        assertEquals("SearchOptions[caseSensitive=false]", SearchOptions.DEFAULT.toString());
        assertEquals("SearchOptions[caseSensitive=true]", SearchOptions.caseSensitive().toString());
    }

    @Test
    void hasNoPublicConstructor() {
        for (Constructor<?> constructor : SearchOptions.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
    }

    @Test
    void isFinalAndNotARecord() {
        assertTrue(Modifier.isFinal(SearchOptions.class.getModifiers()));
        assertFalse(SearchOptions.class.isRecord());
    }
}

package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** A bad stored value never reaches an exception message anywhere in the cause chain (ADR 0005). */
class CastCauseChainTest {

    private static final Map<String, Object> NO_ATTRIBUTES = Collections.emptyMap();
    private static final String SECRET = "jane@x.com";
    private static final String KEY = "contact";

    enum Status { ACTIVE }

    /** A model with the default storage forms. */
    static final class Person extends Model {
    }

    private final Model person = new Person();

    @Test
    void enumReadHidesTheValue() {
        assertHidden(() -> Casts.enumOf(Status.class).get(person, KEY, SECRET, NO_ATTRIBUTES));
    }

    @Test
    void datetimeReadHidesTheValue() {
        assertHidden(() -> Casts.datetime().get(person, KEY, SECRET, NO_ATTRIBUTES));
    }

    @Test
    void intReadHidesTheValue() {
        assertHidden(() -> Casts.integer().get(person, KEY, SECRET, NO_ATTRIBUTES));
    }

    @Test
    void decimalReadHidesTheValue() {
        assertHidden(() -> Casts.decimal(2).get(person, KEY, SECRET, NO_ATTRIBUTES));
    }

    @Test
    void boolReadHidesTheValue() {
        assertHidden(() -> Casts.bool().get(person, KEY, SECRET, NO_ATTRIBUTES));
    }

    @Test
    void jsonReadHidesTheValue() {
        assertHidden(() -> Casts.json().get(person, KEY, "{\"email\":" + SECRET + "}", NO_ATTRIBUTES));
    }

    private static void assertHidden(Executable read) {
        InvalidCastException e = assertThrows(InvalidCastException.class, read);
        for (Throwable t = e; t != null; t = t.getCause()) {
            String described = t.getClass().getName() + ": " + t.getMessage();
            assertFalse(described.contains(SECRET), described);
        }
    }
}

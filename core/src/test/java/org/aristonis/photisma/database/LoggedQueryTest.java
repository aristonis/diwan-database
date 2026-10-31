package org.aristonis.photisma.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoggedQueryTest {

    @Test
    void keepsSqlAndAnUnmodifiableCopyOfTheBindings() {
        List<Object> bindings = new ArrayList<>(Arrays.<Object>asList(1, null, "a"));
        LoggedQuery query = new LoggedQuery("insert into t values (?, ?, ?)", bindings);

        bindings.clear();

        assertEquals("insert into t values (?, ?, ?)", query.sql());
        assertEquals(Arrays.<Object>asList(1, null, "a"), query.bindings());
        assertThrows(UnsupportedOperationException.class, () -> query.bindings().add(2));
    }

    @Test
    void equalsAnotherQueryWithTheSameSqlAndBindings() {
        LoggedQuery first = new LoggedQuery("select 1", Arrays.<Object>asList(1));

        assertEquals(first, new LoggedQuery("select 1", Arrays.<Object>asList(1)));
        assertEquals(first.hashCode(), new LoggedQuery("select 1", Arrays.<Object>asList(1)).hashCode());
        assertNotEquals(first, new LoggedQuery("select 2", Arrays.<Object>asList(1)));
        assertEquals("select 1 [1 binding]", first.toString());
    }

    @Test
    void refusesNullParts() {
        assertThrows(NullPointerException.class, () -> new LoggedQuery(null, new ArrayList<>()));
        assertThrows(NullPointerException.class, () -> new LoggedQuery("select 1", null));
    }
}

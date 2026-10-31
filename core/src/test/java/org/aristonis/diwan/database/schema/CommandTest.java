package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandTest {

    @Test
    void attributesAreCopiedAndReadOnly() {
        List<String> columns = new ArrayList<>(Arrays.asList("a"));
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(Command.COLUMNS, columns);
        attributes.put(Command.INDEX, "i");
        Command command = new Command("unique", attributes);

        columns.add("b");
        attributes.clear();

        assertEquals(Arrays.asList("a"), command.strings(Command.COLUMNS));
        assertEquals("i", command.string(Command.INDEX));
        assertTrue(command.get(Command.INDEX).isPresent());
        assertFalse(command.get(Command.TO).isPresent());
        assertThrows(UnsupportedOperationException.class, () -> command.strings(Command.COLUMNS).add("c"));
        assertEquals("Command[unique, [columns, index]]", command.toString());
    }

    @Test
    void wrongOrMissingAttributesFailClearly() {
        Command command = new Command("x", Collections.singletonMap("n", 1));

        assertThrows(IllegalStateException.class, () -> command.string("n"));
        assertThrows(IllegalStateException.class, () -> command.strings("n"));
        assertThrows(IllegalStateException.class, () -> command.string("missing"));
        assertThrows(IllegalStateException.class, command::column);
        assertThrows(IllegalStateException.class,
                () -> new Command("y", Collections.singletonMap("l", Arrays.asList(1))).strings("l"));
        assertThrows(IllegalStateException.class,
                () -> new Command("z", Collections.singletonMap(Command.COLUMN, "text")).column());
        assertThrows(NullPointerException.class, () -> new Command(null, Collections.<String, Object>emptyMap()));
        assertThrows(NullPointerException.class, () -> new Command("n", Collections.singletonMap("k", null)));
    }

    @Test
    void expressionsCompareByText() {
        assertEquals(Expression.raw("now()"), Expression.raw("now()"));
        assertEquals(Expression.raw("now()").hashCode(), Expression.raw("now()").hashCode());
        assertEquals("now()", Expression.raw("now()").toString());
        assertFalse(Expression.raw("a").equals("a"));
        assertThrows(IllegalArgumentException.class, () -> Expression.raw(" "));
        assertThrows(NullPointerException.class, () -> Expression.raw(null));
    }
}

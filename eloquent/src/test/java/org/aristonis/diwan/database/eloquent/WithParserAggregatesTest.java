package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class WithParserAggregatesTest {

    @Test
    void dotPathNotExpanded() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parseAggregates("posts.comments");

        assertEquals(List.of("posts.comments"), new ArrayList<>(parsed.keySet()));
        assertSame(WithParser.NO_OP, parsed.get("posts.comments"));
    }

    @Test
    void dotPathNotExpandedInMapForm() {
        Map<String, Consumer<EloquentBuilder<?>>> given = new LinkedHashMap<>();
        given.put("posts.comments", q -> { });

        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parseAggregates(given);

        assertEquals(List.of("posts.comments"), new ArrayList<>(parsed.keySet()));
    }

    @Test
    void asAliasKeysStayDistinct() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parseAggregates("posts", "posts as p2");

        assertEquals(Arrays.asList("posts", "posts as p2"), new ArrayList<>(parsed.keySet()));
    }

    @Test
    void rawNameTextIsKeptAsGiven() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parseAggregates("posts  as  n", "posts as Total");

        assertEquals(Arrays.asList("posts  as  n", "posts as Total"), new ArrayList<>(parsed.keySet()));
    }

    @Test
    void columnsAfterColonAreSplitOffTheKey() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parseAggregates("posts:id,title");

        assertEquals(List.of("posts"), new ArrayList<>(parsed.keySet()));
        assertTrue(parsed.get("posts") != WithParser.NO_OP);
    }

    @Test
    void emptyColumnListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> WithParser.parseAggregates("posts:"));
    }

    @Test
    void sameKeyTwiceCombinesConstraintsInCallOrder() {
        List<String> calls = new ArrayList<>();
        Map<String, Consumer<EloquentBuilder<?>>> given = new LinkedHashMap<>();
        given.put("posts", q -> calls.add("first"));
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parseAggregates(given);
        Map<String, Consumer<EloquentBuilder<?>>> more = new LinkedHashMap<>();
        more.put("posts", q -> calls.add("second"));
        WithParser.merge(parsed, more);

        parsed.get("posts").accept(null);

        assertEquals(Arrays.asList("first", "second"), calls);
    }

    @Test
    void sameTextTwiceGivesOneEntry() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parseAggregates("posts", "posts");

        assertEquals(1, parsed.size());
        assertSame(WithParser.NO_OP, parsed.get("posts"));
    }

    @Test
    void callOrderIsKept() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parseAggregates("tags", "posts", "comments");

        assertEquals(Arrays.asList("tags", "posts", "comments"), new ArrayList<>(parsed.keySet()));
    }

    @Test
    void emptyArrayGivesEmptyMap() {
        assertTrue(WithParser.parseAggregates().isEmpty());
        assertTrue(WithParser.parseAggregates(new LinkedHashMap<>()).isEmpty());
    }

    @Test
    void nullsAreRefused() {
        assertThrows(NullPointerException.class, () -> WithParser.parseAggregates("a", null));
        assertThrows(NullPointerException.class, () -> WithParser.parseAggregates((String[]) null));
        Map<String, Consumer<EloquentBuilder<?>>> given = new LinkedHashMap<>();
        given.put("posts", null);
        assertThrows(NullPointerException.class, () -> WithParser.parseAggregates(given));
    }
}

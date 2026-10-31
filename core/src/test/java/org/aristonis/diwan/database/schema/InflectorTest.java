package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InflectorTest {

    @Test
    void regularNounsFollowEnglishRules() {
        assertEquals("users", Inflector.plural("user"));
        assertEquals("categories", Inflector.plural("category"));
        assertEquals("days", Inflector.plural("day"));
        assertEquals("boxes", Inflector.plural("box"));
        assertEquals("buses", Inflector.plural("bus"));
        assertEquals("matches", Inflector.plural("match"));
        assertEquals("wishes", Inflector.plural("wish"));
        assertEquals("quizes", Inflector.plural("quiz"));
    }

    @Test
    void onlyTheLastWordOfASnakeCaseNameChanges() {
        assertEquals("blog_categories", Inflector.plural("blog_category"));
        assertEquals("order_people", Inflector.plural("order_person"));
    }

    @Test
    void irregularAndUncountableNounsAreKnown() {
        assertEquals("people", Inflector.plural("person"));
        assertEquals("children", Inflector.plural("child"));
        assertEquals("statuses", Inflector.plural("status"));
        assertEquals("news", Inflector.plural("news"));
        assertEquals("sheep", Inflector.plural("sheep"));
        assertEquals("", Inflector.plural(""));
        assertEquals("a_", Inflector.plural("a_"));
    }
}

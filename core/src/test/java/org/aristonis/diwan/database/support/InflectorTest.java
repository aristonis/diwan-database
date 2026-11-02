package org.aristonis.diwan.database.support;

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

    @Test
    void snakeSplitsStudlyNamesAtCapitals() {
        assertEquals("user", Inflector.snake("User"));
        assertEquals("blog_post", Inflector.snake("BlogPost"));
        assertEquals("h_t_m_l_page", Inflector.snake("HTMLPage"));
        assertEquals("order2_line", Inflector.snake("Order2Line"));
        assertEquals("", Inflector.snake(""));
    }

    @Test
    void tableNameIsThePluralOfTheSnakeName() {
        assertEquals("blog_posts", Inflector.plural(Inflector.snake("BlogPost")));
        assertEquals("people", Inflector.plural(Inflector.snake("Person")));
    }
}

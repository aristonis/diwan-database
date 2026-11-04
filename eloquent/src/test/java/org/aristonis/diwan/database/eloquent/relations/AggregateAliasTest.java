package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The default aggregate alias: Laravel's
 * {@code Str::snake(preg_replace('/[^[:alnum:][:space:]_]/u', '', "$name $function " . strtolower($column)))}.
 */
class AggregateAliasTest {

    @Test
    void countDropsTheStar() {
        assertEquals("posts_count", RelationAggregates.snakeAlias("posts", "count", "*"));
    }

    @Test
    void maxNamesTheColumn() {
        assertEquals("posts_max_votes", RelationAggregates.snakeAlias("posts", "max", "votes"));
    }

    @Test
    void existsDropsTheStar() {
        assertEquals("posts_exists", RelationAggregates.snakeAlias("posts", "exists", "*"));
    }

    @Test
    void camelCaseRelationIsSnaked() {
        assertEquals("blog_posts_count", RelationAggregates.snakeAlias("blogPosts", "count", "*"));
    }

    @Test
    void noFunctionLeavesTwoSpacesThatCollapse() {
        assertEquals("posts_title", RelationAggregates.snakeAlias("posts", null, "title"));
    }

    @Test
    void dottedColumnLosesTheDot() {
        assertEquals("posts_max_postsvotes", RelationAggregates.snakeAlias("posts", "max", "posts.votes"));
    }

    @Test
    void expressionTextIsStrippedAndSnaked() {
        assertEquals("orders_sum_price_qty", RelationAggregates.snakeAlias("orders", "sum", "price * qty"));
    }

    @Test
    void runsOfWhitespaceCollapse() {
        assertEquals("orders_sum_price_qty", RelationAggregates.snakeAlias("orders", "sum", "price \t*\n  qty"));
    }

    @Test
    void columnIsLowerCasedBeforeSnaking() {
        assertEquals("posts_max_votes", RelationAggregates.snakeAlias("posts", "max", "VOTES"));
    }

    @Test
    void capitalisedRelationIsLowerCased() {
        assertEquals("posts_count", RelationAggregates.snakeAlias("Posts", "count", "*"));
    }

    @Test
    void underscoresAreKept() {
        assertEquals("user_posts_sum_vote_total", RelationAggregates.snakeAlias("user_posts", "sum", "vote_total"));
    }

    @Test
    void nonAsciiLettersAreKept() {
        assertEquals("café_count", RelationAggregates.snakeAlias("café", "count", "*"));
    }
}

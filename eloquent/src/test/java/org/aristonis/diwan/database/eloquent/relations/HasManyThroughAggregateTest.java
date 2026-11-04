package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.SQLException;
import java.util.List;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Aggregate subselects over a hasManyThrough relation: the existence query carries the join to
 * the through table, and the aggregate is the only column of the subselect.
 */
class HasManyThroughAggregateTest {

    private static final String POSTS_JOIN = " from \"posts\" inner join \"users\""
            + " on \"users\".\"id\" = \"posts\".\"user_id\""
            + " where \"countries\".\"id\" = \"users\".\"country_id\"";

    private ThroughDb db;

    @BeforeEach
    void open() throws SQLException {
        db = ThroughDb.open();
        db.seed();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<ThroughModels.Country> countries() {
        return ThroughModels.Country.QUERY.on(db.db).query();
    }

    private static long number(Model model, String key) {
        Object value = model.getAttribute(key);
        assertNotNull(value, key);
        return ((Number) value).longValue();
    }

    @Test
    void aggregatesUseTheJoinedSubSelect() {
        EloquentBuilder<ThroughModels.Country> query = countries().withCount("posts")
                .withSum("posts", "id").withMax("posts", "id").orderBy("id");

        assertEquals("select \"countries\".*, (select count(*)" + POSTS_JOIN + ") as \"posts_count\", "
                + "(select sum(\"posts\".\"id\")" + POSTS_JOIN + ") as \"posts_sum_id\", "
                + "(select max(\"posts\".\"id\")" + POSTS_JOIN + ") as \"posts_max_id\" "
                + "from \"countries\" order by \"id\" asc", query.toSql());
    }

    @Test
    void aggregateValuesMatchTheRelationRows() {
        List<ThroughModels.Country> found = countries().withCount("posts")
                .withSum("posts", "id").withMax("posts", "id").orderBy("id").get();

        for (ThroughModels.Country country : found) {
            List<ThroughModels.Post> posts = country.posts().get();
            long sum = 0;
            long max = 0;
            for (ThroughModels.Post post : posts) {
                long id = ((Number) post.getKey()).longValue();
                sum += id;
                max = Math.max(max, id);
            }
            assertEquals(posts.size(), number(country, "posts_count"));
            if (!posts.isEmpty()) {
                assertEquals(sum, number(country, "posts_sum_id"));
                assertEquals(max, number(country, "posts_max_id"));
            }
        }
    }
}

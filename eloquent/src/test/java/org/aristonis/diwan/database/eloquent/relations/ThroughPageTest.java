package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelPage;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Country;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The offset pages and cursor pages of a hasManyThrough relation. The total counts the joined rows, a
 * page holds related models without the hidden through key, a page does not change the relation, and
 * a cursor page refuses an order that is not on the related table.
 */
class ThroughPageTest {

    private static final String THROUGH_KEY = "laravel_through_key";

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

    @Test
    void paginateCountsTheJoin() {
        HasManyThrough<Post> posts = postsOf(1);
        posts.getQuery().join("users as twin", "twin.country_id", "=", "users.country_id");

        ModelPage<Post> page = posts.orderBy("posts.id").paginate(4, 1);

        assertEquals(6L, page.total());
        assertEquals(Arrays.asList(1, 1, 2, 2), page.items().modelKeys());
        assertEquals(2, page.lastPage());
        assertTrue(db.log.sql().get(0).contains("inner join \"users\" as \"twin\""), db.log.sql().get(0));
        assertNoThroughKey(page.items());
    }

    @Test
    void paginateDoesNotChangeTheRelation() {
        HasManyThrough<Post> posts = postsOf(1);
        String before = posts.getQuery().toSql();

        posts.paginate(2, 1);
        db.log.clear();
        ModelCollection<Post> all = posts.get();

        assertEquals(before, posts.getQuery().toSql());
        assertEquals(3, all.size());
        assertEquals(Collections.singletonList(
                "select \"posts\".*, \"users\".\"country_id\" as \"laravel_through_key\" from \"posts\" "
                + "inner join \"users\" on \"users\".\"id\" = \"posts\".\"user_id\" "
                + "where \"users\".\"country_id\" = ?"), db.log.sql());
    }

    @Test
    void cursorPaginateRefusesThroughTableOrder() {
        HasManyThrough<Post> posts = postsOf(1).orderBy("users.name");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> posts.cursorPaginate());

        assertTrue(failure.getMessage().contains("[users.name]"), failure.getMessage());
        assertEquals(Collections.emptyList(), db.log.sql());
    }

    @Test
    void cursorPaginateRefusesUnqualifiedOrder() {
        HasManyThrough<Post> posts = postsOf(1).orderBy("title");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> posts.cursorPaginate(2));

        assertTrue(failure.getMessage().contains("[title]"), failure.getMessage());
        assertEquals(Collections.emptyList(), db.log.sql());
    }

    private HasManyThrough<Post> postsOf(int countryId) {
        Country country = Country.QUERY.on(db.db).findOrFail(countryId);
        HasManyThrough<Post> posts = country.posts();
        db.log.clear();
        return posts;
    }

    private static void assertNoThroughKey(ModelCollection<Post> items) {
        for (Post item : items) {
            assertFalse(item.getAttributes().containsKey(THROUGH_KEY));
        }
    }
}

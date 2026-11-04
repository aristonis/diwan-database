package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Eager loading of a has-many-through relation: matching per parent, the hidden through key,
 * constraints, and the load methods of a collection.
 */
class HasManyThroughEagerTest {

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
    void eagerLoadMatchesEachParent() {
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db)
                .with("posts").orderBy("id").get();

        assertEquals(Arrays.asList("Post 1", "Post 2", "Post 3"), titles(countries.get(0)));
        assertEquals(Collections.singletonList("Post 4"), titles(countries.get(1)));
        assertTrue(countries.get(2).relationLoaded("posts"));
        assertTrue(posts(countries.get(2)).isEmpty());
    }

    @Test
    void eagerLoadRunsOneQueryForTheFarParentsAndOneForThePosts() {
        ThroughModels.Country.QUERY.on(db.db).with("posts").get();

        assertEquals(2, db.log.sql().size());
        assertTrue(db.log.sql().get(1).contains("inner join \"users\""));
    }

    @Test
    void eagerLimitIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> ThroughModels.Country.QUERY.on(db.db).with("posts", q -> q.limit(2)).get());
    }

    @Test
    void constraintSelectKeepsTheThroughKey() {
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db)
                .with("posts", q -> q.select("title")).orderBy("id").get();

        assertEquals(Arrays.asList("Post 1", "Post 2", "Post 3"), titles(countries.get(0)));
        assertEquals(Collections.singletonList("Post 4"), titles(countries.get(1)));
        assertEquals(Collections.singleton("title"), posts(countries.get(1)).get(0).getAttributes().keySet());
    }

    @Test
    void selectConstraintQualifiesColumns() {
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db)
                .with("posts:id,title").orderBy("id").get();

        String sql = db.log.sql().get(1);
        assertTrue(sql.contains("\"posts\".\"id\""), sql);
        assertTrue(sql.contains("\"posts\".\"title\""), sql);
        assertEquals(Arrays.asList("Post 1", "Post 2", "Post 3"), titles(countries.get(0)));
        assertEquals(Collections.singletonList("Post 4"), titles(countries.get(1)));
    }

    @Test
    void matchWithoutThroughKeyIsRefused() {
        ThroughModels.Country country = ThroughModels.Country.QUERY.on(db.db).find(1).get();
        ThroughModels.Post unread = ThroughModels.Post.QUERY.on(db.db)
                .hydrate(Collections.singletonList(row("id", 1, "title", "Post 1"))).get(0);
        List<ThroughModels.Country> parents = Collections.singletonList(country);
        List<ThroughModels.Post> results = Collections.singletonList(unread);
        HasManyThrough<ThroughModels.Post> relation = country.posts();

        assertThrows(IllegalStateException.class, () -> relation.match(parents, results, "posts"));
    }

    @Test
    void parentsWithTheSameKeyShareResultsAsSeparateCollections() {
        List<Map<String, ?>> rows = Arrays.asList(row("id", 1, "name", "A"), row("id", 1, "name", "B"));
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db).hydrate(rows);
        db.log.clear();

        countries.load("posts");

        assertEquals(1, db.log.sql().size());
        assertEquals(Arrays.asList("Post 1", "Post 2", "Post 3"), titles(countries.get(0)));
        assertEquals(titles(countries.get(0)), titles(countries.get(1)));
        assertNotSame(countries.get(0).getRelation("posts"), countries.get(1).getRelation("posts"));
        assertTrue(db.log.sql().get(0).endsWith(" in (1)"), db.log.sql().get(0));
    }

    @Test
    void loadAndLoadMissingWork() {
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db).all();

        countries.load("posts");
        assertEquals(3, titles(countries.get(0)).size());
        db.log.clear();
        countries.loadMissing("posts");
        assertTrue(db.log.sql().isEmpty());

        countries.get(0).unsetRelation("posts");
        countries.loadMissing("posts");
        assertEquals(1, db.log.sql().size());
        assertEquals(3, titles(countries.get(0)).size());
    }

    @Test
    void loadCountCountsThePostsOfEachCountry() {
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db).all();

        countries.loadCount("posts");

        assertEquals(3L, ((Number) countries.get(0).getAttribute("posts_count")).longValue());
        assertEquals(1L, ((Number) countries.get(1).getAttribute("posts_count")).longValue());
        assertEquals(0L, ((Number) countries.get(2).getAttribute("posts_count")).longValue());
    }

    @Test
    void hiddenKeyIsNotInToMapOrDirty() {
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db)
                .with("posts").orderBy("id").get();

        for (ThroughModels.Post post : posts(countries.get(0))) {
            assertFalse(post.getAttributes().containsKey(THROUGH_KEY));
            assertFalse(post.toMap().containsKey(THROUGH_KEY));
            assertFalse(post.isDirty());
            assertTrue(post.getDirty().isEmpty());
        }
    }

    @Test
    void lazyReadAfterEagerLoadStillWorks() {
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db)
                .with("posts").orderBy("id").get();

        assertEquals(3, countries.get(0).posts().get().size());
        assertEquals(Arrays.asList("Post 1", "Post 2", "Post 3"), titles(countries.get(0)));
    }

    @SuppressWarnings("unchecked")
    private static ModelCollection<ThroughModels.Post> posts(ThroughModels.Country country) {
        return (ModelCollection<ThroughModels.Post>) country.getRelation("posts");
    }

    private static List<String> titles(ThroughModels.Country country) {
        List<String> titles = new ArrayList<>();
        for (ThroughModels.Post post : posts(country)) {
            titles.add((String) post.getAttribute("title"));
        }
        return titles;
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }
}

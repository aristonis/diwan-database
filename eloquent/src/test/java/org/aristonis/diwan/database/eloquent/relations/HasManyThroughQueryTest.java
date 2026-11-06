package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.LazyLoadingViolationException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Post;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lazy read of a has-many-through relation: the rows and the SQL of the read, the empty results,
 * duplicate join rows, the hidden through key, a relation with a declared {@code orWhere}, and the
 * lazy-loading guard.
 */
class HasManyThroughQueryTest {

    private static final String LAZY_SQL = "select \"posts\".*, \"users\".\"country_id\" as \"laravel_through_key\""
            + " from \"posts\" inner join \"users\" on \"users\".\"id\" = \"posts\".\"user_id\""
            + " where \"users\".\"country_id\" = ?";

    private ThroughDb db;

    @BeforeEach
    void open() throws SQLException {
        db = ThroughDb.open();
        db.seed();
    }

    @AfterEach
    void close() throws SQLException {
        ModelQuery.preventLazyLoading(false);
        db.close();
    }

    @Test
    void unsavedFarParentReadsNothing() {
        // Arrange
        ThroughModels.Country country = ThroughModels.Country.QUERY.on(db.db).newModel();

        // Act
        ModelCollection<Post> posts = country.posts().getResults();

        // Assert
        assertTrue(posts.isEmpty());
        assertTrue(db.log.sql().isEmpty());
    }

    @Test
    void countryWithoutPostsGivesEmptyList() {
        // Arrange: Japan gets a user who has written nothing.
        insert("users", "id", 4, "country_id", 3, "name", "Dai");
        ThroughModels.Country japan = ThroughModels.Country.QUERY.on(db.db).findOrFail(3);
        db.log.clear();

        // Act
        ModelCollection<Post> posts = japan.posts().get();

        // Assert
        assertTrue(posts.isEmpty());
    }

    @Test
    void otherCountriesPostsAreNotReturned() {
        // Arrange
        ThroughModels.Country united = ThroughModels.Country.QUERY.on(db.db).findOrFail(1);

        // Act
        List<Integer> ids = sortedIds(united.posts().get());

        // Assert: post 4 belongs to a user of France.
        assertEquals(Arrays.asList(1, 2, 3), ids);
    }

    @Test
    void duplicateThroughRowsRepeatTheRelatedRow() {
        // Arrange: the through local key is not unique, so each post joins both of the country's users.
        CountryRelations united = CountryRelations.QUERY.on(db.db).findOrFail(1);

        // Act
        List<Integer> ids = sortedIds(united.duplicatePosts().get());

        // Assert
        assertEquals(Arrays.asList(1, 1, 2, 2), ids);
    }

    @Test
    void throughKeyIsNotAnAttribute() {
        // Arrange
        ThroughModels.Country united = ThroughModels.Country.QUERY.on(db.db).findOrFail(1);
        Post post = united.posts().get().get(0);

        // Act
        Map<String, Object> map = post.toMap();

        // Assert
        assertTrue(map.containsKey("title"));
        assertFalse(map.containsKey("laravel_through_key"));
        assertFalse(post.getAttributes().containsKey("laravel_through_key"));
        assertFalse(post.toJson().contains("laravel_through_key"));
        assertFalse(post.isDirty("laravel_through_key"));
    }

    @Test
    void declaredOrWhereBehaviourIsRecorded() {
        // Arrange
        CountryRelations united = CountryRelations.QUERY.on(db.db).findOrFail(1);

        // Act
        List<Integer> ids = sortedIds(united.postsOrDeclared().get());

        // Assert: recorded behaviour. The declared orWhere is not nested under the parent
        // constraint, so post 4 of France is returned for the United States.
        assertEquals(Arrays.asList(1, 4), ids);
    }

    @Test
    void lazyLoadingGuardApplies() {
        // Arrange
        ModelQuery.preventLazyLoading(true);
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db).query().get();
        db.log.clear();

        // Act
        LazyLoadingViolationException violation = assertThrows(LazyLoadingViolationException.class,
                () -> countries.get(0).related("posts"));

        // Assert
        assertEquals("posts", violation.getRelation());
        assertTrue(db.log.sql().isEmpty());
    }

    @Test
    void lazyReadSql() {
        // Arrange
        ThroughModels.Country united = ThroughModels.Country.QUERY.on(db.db).findOrFail(1);
        db.log.clear();

        // Act
        ModelCollection<Post> posts = united.posts().get();

        // Assert
        assertEquals(Collections.singletonList(LAZY_SQL), db.log.sql());
        assertEquals(Collections.singletonList(1), db.log.bindingCounts());
        assertEquals(3, posts.size());
    }

    private void insert(String table, Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        db.db.table(table).insert(row);
    }

    private static List<Integer> sortedIds(ModelCollection<?> models) {
        List<Integer> ids = new ArrayList<>();
        for (Model model : models) {
            ids.add(((Number) model.getKey()).intValue());
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * A country with relations that declare clauses or a non-unique through key.
     */
    static final class CountryRelations extends Model {
        static final ModelQuery<CountryRelations> QUERY = ModelQuery.of(CountryRelations.class,
                CountryRelations::new);

        @Override
        protected String table() {
            return "countries";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        HasManyThrough<Post> postsOrDeclared() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "id", "id")
                    .where("posts.title", "Post 1")
                    .orWhere("posts.title", "Post 4");
        }

        HasManyThrough<Post> duplicatePosts() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "id", "country_id");
        }
    }
}

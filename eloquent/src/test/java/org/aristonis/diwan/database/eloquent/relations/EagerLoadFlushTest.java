package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code without} and {@code withOnly} take eager loads off a builder before it runs. */
class EagerLoadFlushTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("profiles", Map.of("id", 1, "user_id", 1, "bio", "hi"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<User> users() {
        return User.QUERY.on(db.db).query();
    }

    @Test
    void withoutRemovesTheNamedEagerLoadOnly() {
        // Arrange
        EloquentBuilder<User> query = users().with("posts", "profile");

        // Act
        ModelCollection<User> found = query.without("posts").get();

        // Assert
        assertEquals(2, db.sql().size());
        assertFalse(found.get(0).relationLoaded("posts"));
        assertTrue(found.get(0).relationLoaded("profile"));
    }

    @Test
    void withoutOfEveryLoadRunsOnlyTheMainQuery() {
        // Arrange
        EloquentBuilder<User> query = users().with("posts", "profile");

        // Act
        ModelCollection<User> found = query.without("posts", "profile").get();

        // Assert
        assertEquals(1, db.sql().size());
        assertTrue(query.getEagerLoads().isEmpty());
        assertFalse(found.get(0).relationLoaded("posts"));
    }

    @Test
    void withoutKeepsTheNestedPathOfThatRelation() {
        // Arrange
        EloquentBuilder<User> query = users().with("posts.comments");

        // Act
        query.without("posts");

        // Assert
        List<String> paths = Arrays.asList(query.getEagerLoads().keySet().toArray(new String[0]));
        assertEquals(Collections.singletonList("posts.comments"), paths);
    }

    @Test
    void withOnlyReplacesEveryEagerLoad() {
        // Arrange
        EloquentBuilder<User> query = users().with("posts");

        // Act
        ModelCollection<User> found = query.withOnly("profile").get();

        // Assert
        assertEquals(Collections.singletonList("profile"),
                Arrays.asList(query.getEagerLoads().keySet().toArray(new String[0])));
        assertFalse(found.get(0).relationLoaded("posts"));
        assertTrue(found.get(0).relationLoaded("profile"));
    }
}

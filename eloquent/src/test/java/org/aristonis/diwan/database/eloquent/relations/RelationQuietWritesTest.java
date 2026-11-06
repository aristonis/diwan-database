package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Writes through a has-many relation inside {@code Model.withoutEvents}: the rows are written and no
 * model event fires. Diwan has no relation-level quiet method; the muting scope takes its place.
 */
class RelationQuietWritesTest {

    private RelationDb db;
    private final AtomicInteger creating = new AtomicInteger();
    private final AtomicInteger saved = new AtomicInteger();

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        Post.QUERY.events().creating(post -> {
            creating.incrementAndGet();
            return true;
        });
        Post.QUERY.events().saved(post -> saved.incrementAndGet());
    }

    @AfterEach
    void close() throws SQLException {
        Post.QUERY.events().forget();
        db.close();
    }

    private User ann() {
        return User.QUERY.on(db.db).findOrFail(1);
    }

    @Test
    void createFiresEventsOutsideTheMutingScope() {
        // Arrange
        User ann = ann();

        // Act
        ann.posts().create(Map.of("title", "loud"));

        // Assert
        assertEquals(1, creating.get());
        assertEquals(1, saved.get());
    }

    @Test
    void createInsideWithoutEventsWritesTheRowAndFiresNothing() {
        // Arrange
        User ann = ann();

        // Act
        Model.withoutEvents(() -> {
            ann.posts().create(Map.of("title", "quiet"));
        });

        // Assert
        assertEquals(0, creating.get());
        assertEquals(0, saved.get());
        assertEquals(1, ann.posts().getResults().size());
    }

    @Test
    void saveInsideWithoutEventsFiresNothingAndSetsTheForeignKey() {
        // Arrange
        User ann = ann();
        Post draft = ann.posts().make(Map.of("title", "draft"));

        // Act
        Model.withoutEvents(() -> {
            ann.posts().save(draft);
        });

        // Assert
        assertEquals(0, creating.get());
        assertEquals(0, saved.get());
        assertTrue(draft.exists());
        assertEquals(1L, ((Number) draft.getAttribute("user_id")).longValue());
    }
}

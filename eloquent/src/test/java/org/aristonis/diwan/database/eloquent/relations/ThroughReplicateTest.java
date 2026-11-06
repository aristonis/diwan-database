package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Country;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A model read through a has-many-through relation can be replicated and saved as a new row. */
class ThroughReplicateTest {

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

    private long postCount() {
        return ((Number) db.real.selectOne("select count(*) as c from posts").orElseThrow().get("c")).longValue();
    }

    @Test
    void replicaOfAThroughReadModelCarriesNoHelperColumn() {
        // Arrange
        Country country = Country.QUERY.on(db.db).findOrFail(1);
        Post post = country.posts().getResults().get(0);

        // Act
        Post replica = post.replicate();

        // Assert
        assertFalse(replica.getAttributes().containsKey("laravel_through_key"));
        assertFalse(replica.exists());
        assertEquals(post.getAttribute("title"), replica.getAttribute("title"));
    }

    @Test
    void replicaOfAThroughReadModelSavesAsANewRow() {
        // Arrange
        Country country = Country.QUERY.on(db.db).findOrFail(1);
        Post post = country.posts().getResults().get(0);
        long before = postCount();
        Post replica = post.replicate();
        replica.setAttribute("title", post.getAttribute("title") + " v2");

        // Act
        boolean saved = replica.save();

        // Assert
        assertTrue(saved);
        assertEquals(before + 1, postCount());
        assertTrue(replica.exists());
    }
}

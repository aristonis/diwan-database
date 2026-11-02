package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code loadMissing} on a model and a collection: only what is not loaded yet is read. */
class LoadMissingTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        for (int id = 1; id <= 3; id++) {
            db.seed("users", Map.of("id", id, "name", "u" + id));
            db.seed("posts", Map.of("id", id, "user_id", id, "title", "p" + id));
        }
        db.seed("posts", Map.of("id", 4, "user_id", 9, "title", "orphan"));
        db.seed("comments", Map.of("id", 1, "post_id", 1, "body", "x"));
        db.seed("comments", Map.of("id", 2, "post_id", 3, "body", "y"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void loadedNullNotRetried() {
        Post orphan = Post.QUERY.on(db.db).findOrFail(4);
        orphan.setRelation("author", null);
        db.clearLog();

        assertSame(orphan, orphan.loadMissing("author"));

        assertEquals(Collections.emptyList(), db.sql());
        assertNull(orphan.getRelation("author"));
    }

    @Test
    void loadMissingOnAModelLoadsWhatIsMissing() {
        Post orphan = Post.QUERY.on(db.db).findOrFail(4);
        db.clearLog();

        orphan.loadMissing("author");

        assertEquals(1, db.sql().size());
        assertTrue(orphan.relationLoaded("author"));
        assertNull(orphan.getRelation("author"));
    }

    @Test
    void partialPaths() {
        ModelCollection<User> users = User.QUERY.on(db.db).all();
        users.get(0).load("posts.comments");
        db.clearLog();

        assertSame(users, users.loadMissing("posts.comments"));

        List<String> sql = db.sql();
        assertEquals(2, sql.size(), sql.toString());
        assertTrue(sql.get(0).startsWith("select * from \"posts\"") && sql.get(0).endsWith("in (2, 3)"), sql.get(0));
        assertTrue(sql.get(1).startsWith("select * from \"comments\"") && sql.get(1).endsWith("in (2, 3)"), sql.get(1));
        Post third = users.get(2).<Post>relatedMany("posts").get(0);
        assertEquals(1, third.relatedMany("comments").size());
    }
}

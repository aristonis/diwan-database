package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MorphManyOneTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphModels.createTables(db);
        MorphModels.register();
        MorphModels.mapAliases();
        db.seed("morph_posts", Map.of("id", 1, "title", "a"));
        db.seed("morph_comments", Map.of("id", 1, "body", "skip", "commentable_type", "post", "commentable_id", 1));
        db.seed("morph_comments", Map.of("id", 2, "body", "keep", "commentable_type", "post", "commentable_id", 1));
        db.seed("morph_comments", Map.of("id", 3, "body", "keep", "commentable_type", "video", "commentable_id", 1));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            found++;
        }
        return found;
    }

    @Test
    void oneKeepsTheClausesRecordedOnTheRelationAndTheTypeConditionOnce() {
        Post post = Post.QUERY.on(db.db).findOrFail(1);
        db.clearLog();

        Comment found = post.comments().where("body", "=", "keep").one().getResults();

        assertEquals(2, found.getKey());
        String sql = db.sql().get(0);
        assertEquals(1, count(sql, "\"body\""), sql);
        assertEquals(1, count(sql, "\"commentable_type\" = ?"), sql);
        assertEquals(1, count(sql, "\"commentable_id\" = ?"), sql);
    }

    @Test
    void oneAfterTheQueryWasReadKeepsTheClausesOnce() {
        Post post = Post.QUERY.on(db.db).findOrFail(1);
        MorphMany<Comment> relation = post.comments().where("body", "=", "keep");
        relation.getQuery();
        db.clearLog();

        Comment found = relation.one().getResults();

        assertEquals(2, found.getKey());
        String sql = db.sql().get(0);
        assertEquals(1, count(sql, "\"body\""), sql);
        assertEquals(1, count(sql, "\"commentable_type\" = ?"), sql);
    }
}

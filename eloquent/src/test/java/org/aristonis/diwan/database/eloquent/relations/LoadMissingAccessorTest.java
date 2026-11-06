package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.casts.Attribute;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code loadMissing} reads the relations it needs and never runs an attribute accessor. */
class LoadMissingAccessorTest {

    private static final AtomicInteger BODY_READS = new AtomicInteger();

    /** A comment whose {@code body} accessor counts every read. */
    static final class Remark extends Model {
        static final ModelQuery<Remark> QUERY = ModelQuery.of(Remark.class, Remark::new);

        @Override
        protected String table() {
            return "comments";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected Map<String, Attribute<?>> accessors() {
            return Map.of("body", Attribute.<String>get((model, value, attributes) -> {
                BODY_READS.incrementAndGet();
                return String.valueOf(value);
            }));
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Remark.class).add("post", Remark::post);
        }

        BelongsTo<Post> post() {
            return belongsTo(Post.class, "post_id");
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        BODY_READS.set(0);
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("comments", Map.of("id", 1, "post_id", 1, "body", "hello"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void nestedLoadMissingRunsNoAccessor() {
        // Arrange
        ModelCollection<Remark> remarks = Remark.QUERY.on(db.db).all();
        db.clearLog();

        // Act
        remarks.loadMissing("post.author");

        // Assert
        assertEquals(2, db.sql().size());
        assertEquals(0, BODY_READS.get());
        Post post = remarks.get(0).related("post");
        assertTrue(post.relationLoaded("author"));
    }

    @Test
    void loadMissingOnAModelRunsNoAccessor() {
        // Arrange
        Remark remark = Remark.QUERY.on(db.db).findOrFail(1);

        // Act
        remark.loadMissing("post");

        // Assert
        assertEquals(0, BODY_READS.get());
        assertTrue(remark.relationLoaded("post"));
    }
}

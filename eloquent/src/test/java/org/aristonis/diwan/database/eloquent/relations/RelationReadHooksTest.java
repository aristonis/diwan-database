package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The relation read hooks: the default read query and the eager completion, both the unchanged
 * behaviour the existing relation kinds rely on.
 */
class RelationReadHooksTest {

    private static final String LAZY = "select * from \"posts\" where \"posts\".\"user_id\" = ?"
            + " and \"posts\".\"user_id\" is not null";

    private static final String EAGER_HAS_MANY = "select \"title\", \"posts\".\"user_id\" from \"posts\""
            + " where \"posts\".\"user_id\" in (1, 2)";

    private static final String EAGER_BELONGS_TO = "select \"name\", \"users\".\"id\" from \"users\""
            + " where \"users\".\"id\" in (1, 2)";

    private RelationDb db;

    private final RelationAccessImpl access = new RelationAccessImpl();

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        db.seed("posts", Map.of("id", 3, "user_id", 2, "title", "c"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private User user(int id) {
        User user = User.QUERY.on(db.db).findOrFail(id);
        db.clearLog();
        return user;
    }

    private ModelCollection<Post> posts() {
        return Post.QUERY.on(db.db).all();
    }

    // ---- default read query

    @Test
    void defaultReadQueryIsConstrainedQuery() {
        User ann = user(1);

        ModelCollection<Post> posts = ann.posts().get();

        assertEquals(Arrays.asList(1, 2), posts.modelKeys());
        assertEquals(Collections.singletonList(LAZY), db.sql());
        assertEquals(Collections.singletonList(1), db.queries().get(0).bindings());
    }

    // ---- default eager completion

    @Test
    void defaultCompleteEagerQueryAddsMatchColumn() {
        ModelCollection<User> users = User.QUERY.on(db.db).all();
        db.clearLog();
        access.load(users, Collections.singletonMap("posts", q -> q.select("title")));

        assertEquals(2, users.get(0).relatedMany("posts").size());
        assertEquals(Collections.singletonList(EAGER_HAS_MANY), db.sql());
    }

    // ---- eager SQL unchanged

    @Test
    void eagerSqlUnchangedForBelongsTo() {
        ModelCollection<Post> posts = posts();
        db.clearLog();
        access.load(posts, Collections.singletonMap("author", q -> q.select("name")));

        assertEquals(Collections.singletonList(EAGER_BELONGS_TO), db.sql());
    }

    // ---- get() stays final

    @Test
    void getStillFinal() {
        User ann = user(1);
        Relation<Post, ?> relation = ann.posts();

        ModelCollection<Post> results = relation.get();

        assertEquals(Arrays.asList(1, 2), results.modelKeys());
    }
}

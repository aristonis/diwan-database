package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BelongsToTest {

    private RelationDb db;

    /** A profile over the profiles table whose bio names its user: a text owner key. */
    static final class NamedProfile extends Model {
        static final ModelQuery<NamedProfile> QUERY = ModelQuery.of(NamedProfile.class, NamedProfile::new);

        @Override
        protected String table() {
            return "profiles";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(NamedProfile.class).add("owner", NamedProfile::owner);
        }

        public BelongsTo<User> owner() {
            return belongsTo(User.class, "bio", "name");
        }
    }

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "title", "orphan"));
        db.seed("posts", Map.of("id", 3, "user_id", 9, "title", "gone"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private Post post(int id) {
        Post post = Post.QUERY.on(db.db).findOrFail(id);
        db.clearLog();
        return post;
    }

    @Test
    void lazyQueryFindsTheOwner() {
        User author = post(1).author().getResults();

        assertEquals("Ann", author.getAttribute("name"));
        assertEquals(Collections.singletonList("select * from \"users\" where \"users\".\"id\" = ? limit 1"),
                db.sql());
        assertEquals(Collections.singletonList(1), db.queries().get(0).bindings());
    }

    @Test
    void nullForeignKeyReturnsNullWithoutQuery() {
        Post orphan = post(2);

        assertNull(orphan.related("author"));
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void missingOwnerIsNull() {
        Post post = post(3);

        assertNull(post.related("author"));
        assertEquals(1, db.sql().size());
        assertTrue(post.relationLoaded("author"));
    }

    @Test
    void keysAreTheGivenOnes() {
        BelongsTo<User> author = post(1).author();

        assertEquals("user_id", author.getForeignKeyName());
        assertEquals("posts.user_id", author.getQualifiedForeignKeyName());
        assertEquals("id", author.getOwnerKeyName());
        assertEquals("users.id", author.getQualifiedOwnerKeyName());
    }

    @Test
    void customOwnerKey() {
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("profiles", Map.of("id", 1, "user_id", 1, "bio", "Bob"));
        db.seed("profiles", Map.of("id", 2, "user_id", 1, "bio", "Ann"));
        List<NamedProfile> profiles = NamedProfile.QUERY.on(db.db).all();
        db.clearLog();

        User lazy = profiles.get(0).owner().getResults();
        new RelationAccessImpl().load(profiles, Collections.singletonMap("owner", q -> {
        }));

        assertEquals(2, lazy.getKey());
        assertEquals("select * from \"users\" where \"users\".\"name\" = ? limit 1", db.sql().get(0));
        assertEquals("select * from \"users\" where \"users\".\"name\" in (?, ?)", db.sql().get(1));
        assertEquals(Arrays.asList("Bob", "Ann"), db.queries().get(1).bindings());
        assertEquals("Bob", profiles.get(0).<User>related("owner").getAttribute("name"));
        assertEquals("Ann", profiles.get(1).<User>related("owner").getAttribute("name"));
    }
}

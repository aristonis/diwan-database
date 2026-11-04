package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.casts.InvalidCastException;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.aristonis.diwan.database.query.Expression;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The builder's {@code withCasts} overlay. */
class WithCastsTest {

    private static final Expression FLAG = Expression.raw("1 as \"flag\"");

    /** A user that records, in {@code retrieved()}, what its {@code flag} read as. */
    static final class Watcher extends Model {
        static final ModelQuery<Watcher> QUERY = ModelQuery.of(Watcher.class, Watcher::new);

        private Object seenFlag;

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected void retrieved() {
            seenFlag = getAttribute("flag");
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<User> users() {
        return User.QUERY.on(db.db).query();
    }

    @Test
    void appliedToHydratedModels() {
        ModelCollection<User> found = users().select("*").addSelect(FLAG)
                .withCasts(Map.of("flag", "boolean")).get();

        assertEquals(2, found.size());
        for (int i = 0; i < found.size(); i++) {
            assertEquals(Boolean.TRUE, found.get(i).getAttribute("flag"));
            assertTrue(found.get(i).getCasts().containsKey("flag"));
        }
    }

    @Test
    void acceptsCastInstances() {
        User ann = users().select("*").addSelect(FLAG).withCasts(Map.of("flag", Casts.bool()))
                .orderBy("id").first().orElseThrow();

        assertEquals(Boolean.TRUE, ann.getAttribute("flag"));
    }

    @Test
    void notOnEagerChildren() {
        User ann = users().withCasts(Map.of("flag", "boolean")).with("posts").orderBy("id").first().orElseThrow();

        assertTrue(ann.getCasts().containsKey("flag"));
        Post post = (Post) ((ModelCollection<?>) ann.getRelation("posts")).get(0);
        assertFalse(post.getCasts().containsKey("flag"));
    }

    @Test
    void copyKeepsOverlay() {
        // firstOrCreate reads through a copy of the builder
        User ann = users().select("*").addSelect(FLAG).withCasts(Map.of("flag", "boolean"))
                .firstOrCreate(Map.of("name", "Ann"));

        assertEquals(Boolean.TRUE, ann.getAttribute("flag"));
    }

    @Test
    void appliedBeforeRetrieved() {
        Watcher watcher = Watcher.QUERY.on(db.db).query().select("*").addSelect(FLAG)
                .withCasts(Map.of("flag", "boolean")).orderBy("id").first().orElseThrow();

        assertEquals(Boolean.TRUE, watcher.seenFlag);
    }

    @Test
    void twoWithExistsAndWithCastsAllSurvive() {
        User ann = users().withExists("posts").withExists("profile")
                .withCasts(Map.of("name", "string")).orderBy("id").first().orElseThrow();

        assertSame(Boolean.TRUE, ann.getAttribute("posts_exists"));
        assertSame(Boolean.FALSE, ann.getAttribute("profile_exists"));
        assertTrue(ann.getCasts().containsKey("name"));
    }

    @Test
    void laterCallWinsForSameKey() {
        User ann = users().select("*").addSelect(FLAG).withCasts(Map.of("flag", "boolean"))
                .withCasts(Map.of("flag", "string")).orderBy("id").first().orElseThrow();

        assertEquals("1", ann.getAttribute("flag"));
    }

    @Test
    void callerMapIsNotKept() {
        Map<String, Object> casts = new LinkedHashMap<>();
        casts.put("flag", "boolean");
        EloquentBuilder<User> query = users().select("*").addSelect(FLAG).withCasts(casts);
        casts.put("flag", "string");

        assertEquals(Boolean.TRUE, query.orderBy("id").first().orElseThrow().getAttribute("flag"));
    }

    @Test
    void badCastFailsAtTheCall() {
        assertThrows(InvalidCastException.class, () -> users().withCasts(Map.of("flag", "no_such_cast")));
        assertThrows(IllegalArgumentException.class, () -> users().withCasts(Map.of("flag", 5)));
        assertThrows(NullPointerException.class, () -> users().withCasts(null));
    }

    @Test
    void emptyMapChangesNothing() {
        User ann = users().withCasts(Collections.emptyMap()).orderBy("id").first().orElseThrow();

        assertFalse(ann.getCasts().containsKey("flag"));
    }
}

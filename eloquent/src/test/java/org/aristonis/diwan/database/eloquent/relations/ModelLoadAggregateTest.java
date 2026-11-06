package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The {@code load*} forms on a single model: a one-model list through the collection loader. */
class ModelLoadAggregateTest {

    private static final String COUNT_SQL = "select \"id\", (select count(*) from \"posts\" where \"users\".\"id\" = "
            + "\"posts\".\"user_id\") as \"posts_count\" from \"users\" where \"users\".\"id\" in (2)";

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "u1"));
        db.seed("users", Map.of("id", 2, "name", "u2"));
        db.seed("posts", Map.of("id", 1, "user_id", 2, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 2, "title", "b"));
        db.seed("posts", Map.of("id", 3, "user_id", 1, "title", "c"));
        db.seed("profiles", Map.of("id", 1, "user_id", 2, "bio", "x"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void singleModelReturnsTheModel() {
        User user = User.QUERY.on(db.db).findOrFail(2);
        db.clearLog();

        assertSame(user, user.loadCount("posts"));

        assertEquals(Collections.singletonList(COUNT_SQL), db.sql());
        assertEquals(2L, ((Number) user.getAttribute("posts_count")).longValue());
        assertFalse(user.isDirty("posts_count"));
    }

    @Test
    void sameSqlAsTheCollectionForm() {
        User user = User.QUERY.on(db.db).findOrFail(2);
        ModelCollection<User> users = User.QUERY.on(db.db).query().whereKey(2).get();
        db.clearLog();

        user.loadCount("posts");
        users.loadCount("posts");

        List<String> sql = db.sql();
        assertEquals(2, sql.size(), sql.toString());
        assertEquals(sql.get(1), sql.get(0));
    }

    @Test
    void otherModelsAreNotTouched() {
        User user = User.QUERY.on(db.db).findOrFail(2);
        user.setAttribute("name", "changed");

        user.loadCount("posts");

        assertTrue(user.isDirty("name"));
        assertFalse(user.isDirty("posts_count"));
    }

    @Test
    void mapFormAppliesTheConstraint() {
        User user = User.QUERY.on(db.db).findOrFail(2);
        Map<String, Consumer<EloquentBuilder<?>>> relations = Map.of("posts", q -> q.where("title", "a"));

        assertSame(user, user.loadCount(relations));

        assertEquals(1L, ((Number) user.getAttribute("posts_count")).longValue());
    }

    @Test
    void everyFunctionReturnsTheModel() {
        User user = User.QUERY.on(db.db).findOrFail(2);

        assertSame(user, user.loadMax("posts", "id"));
        assertSame(user, user.loadMin("posts", "id"));
        assertSame(user, user.loadSum("posts", "id"));
        assertSame(user, user.loadAvg("posts", "id"));
        assertSame(user, user.loadAggregate("posts", "id", "max"));

        assertEquals(2L, ((Number) user.getAttribute("posts_max_id")).longValue());
        assertEquals(1L, ((Number) user.getAttribute("posts_min_id")).longValue());
        assertEquals(3L, ((Number) user.getAttribute("posts_sum_id")).longValue());
        assertEquals(1.5, ((Number) user.getAttribute("posts_avg_id")).doubleValue());
    }

    @Test
    void mapFormsOfEveryFunctionReturnTheModel() {
        User user = User.QUERY.on(db.db).findOrFail(2);
        Map<String, Consumer<EloquentBuilder<?>>> posts = Map.of("posts", q -> { });

        assertSame(user, user.loadMax(posts, "id"));
        assertSame(user, user.loadMin(posts, "id"));
        assertSame(user, user.loadSum(posts, "id"));
        assertSame(user, user.loadAvg(posts, "id"));
        assertSame(user, user.loadExists(posts));
        assertSame(user, user.loadAggregate(posts, "id", "min"));

        assertEquals(3L, ((Number) user.getAttribute("posts_sum_id")).longValue());
        assertEquals(Boolean.TRUE, user.getAttribute("posts_exists"));
    }

    @Test
    void existsIsBooleanThroughTheOverlay() {
        User first = User.QUERY.on(db.db).findOrFail(1);
        User second = User.QUERY.on(db.db).findOrFail(2);

        first.loadExists("profile");
        second.loadExists("profile");

        assertEquals(Boolean.FALSE, first.getAttribute("profile_exists"));
        assertEquals(Boolean.TRUE, second.getAttribute("profile_exists"));
    }

    @Test
    void noFunctionFormReadsOneColumn() {
        User user = User.QUERY.on(db.db).findOrFail(1);

        assertSame(user, user.loadAggregate("posts", "title"));

        assertEquals("c", user.getAttribute("posts_title"));
    }

    @Test
    void unsavedModelRunsNoQuery() {
        User user = new User();
        db.clearLog();

        assertSame(user, user.loadCount("posts"));

        assertEquals(Collections.emptyList(), db.sql());
        assertFalse(user.hasAttribute("posts_count"));
    }

    @Test
    void unknownRelationThrowsBeforeAnyQuery() {
        User user = User.QUERY.on(db.db).findOrFail(1);
        db.clearLog();

        assertThrows(RelationNotFoundException.class, () -> user.loadCount("postz"));

        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void nullArgumentsThrow() {
        User user = User.QUERY.on(db.db).findOrFail(1);

        assertThrows(NullPointerException.class, () -> user.loadCount((String[]) null));
        assertThrows(NullPointerException.class, () -> user.loadSum((String) null, "id"));
    }
}

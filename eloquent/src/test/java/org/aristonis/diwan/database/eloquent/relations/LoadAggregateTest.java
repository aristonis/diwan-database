package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.EagerLoadTest.TextUser;
import org.aristonis.diwan.database.eloquent.relations.RelationExistenceTest.Category;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The collection's {@code load*} aggregate methods: one key-only
 * query, values copied onto the loaded models as clean attributes, missing rows skipped.
 */
class LoadAggregateTest {

    private static final String POSTS_OF_USER = "from \"posts\" where \"users\".\"id\" = \"posts\".\"user_id\"";
    private static final String COUNT_POSTS = "(select count(*) " + POSTS_OF_USER + ") as \"posts_count\"";
    private static final String KEYS = " from \"users\" where \"users\".\"id\" in (1, 2, 3)";

    /** A user over the users table that counts its {@code retrieved()} calls. */
    static final class CountingUser extends Model {
        static final ModelQuery<CountingUser> QUERY = ModelQuery.of(CountingUser.class, CountingUser::new);
        static final AtomicInteger RETRIEVED = new AtomicInteger();

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(CountingUser.class).add("posts", CountingUser::posts);
        }

        @Override
        protected void retrieved() {
            RETRIEVED.incrementAndGet();
        }

        public HasMany<Post> posts() {
            return hasMany(Post.class, "user_id");
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("users", Map.of("id", 3, "name", "Cid"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        db.seed("posts", Map.of("id", 3, "user_id", 2, "title", "c"));
        db.seed("profiles", Map.of("id", 1, "user_id", 1, "bio", "hi"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private ModelCollection<User> users() {
        ModelCollection<User> users = User.QUERY.on(db.db).query().orderBy("id").get();
        db.clearLog();
        return users;
    }

    private ModelCollection<User> collectionOf(List<User> models) {
        return Access.eloquent().newCollection(User.QUERY.on(db.db).query(), models);
    }

    private void deleteUser(int id) {
        SQLiteQuery.of(db.real).table("users").where("id", id).delete();
    }

    private static long number(Model model, String key) {
        Object value = model.getAttribute(key);
        assertNotNull(value, key);
        return ((Number) value).longValue();
    }

    private static Map<String, Consumer<EloquentBuilder<?>>> one(String name, Consumer<EloquentBuilder<?>> constraint) {
        Map<String, Consumer<EloquentBuilder<?>>> relations = new LinkedHashMap<>();
        relations.put(name, constraint);
        return relations;
    }

    @Test
    void oneQueryKeyOnlySelect() {
        ModelCollection<User> users = users();

        ModelCollection<User> returned = users.loadCount("posts");

        assertSame(users, returned);
        assertEquals(Collections.singletonList("select \"id\", " + COUNT_POSTS + KEYS), db.sql());
        assertEquals(2L, number(users.get(0), "posts_count"));
        assertEquals(1L, number(users.get(1), "posts_count"));
        assertEquals(0L, number(users.get(2), "posts_count"));
    }

    @Test
    void emptyCollectionNoQuery() {
        ModelCollection<User> none = User.QUERY.on(db.db).query().where("id", 99).get();
        db.clearLog();

        assertSame(none, none.loadCount("posts"));
        assertSame(none, none.loadExists("profile"));
        assertSame(none, none.loadMax("posts", "id"));
        assertTrue(db.sql().isEmpty());
    }

    @Test
    void deletedRowSkipped() {
        ModelCollection<User> users = users();
        deleteUser(2);

        users.loadCount("posts");

        assertEquals(2L, number(users.get(0), "posts_count"));
        assertNull(users.get(1).getAttribute("posts_count"));
        assertEquals(0L, number(users.get(2), "posts_count"));
    }

    @Test
    void noRowsReturned() {
        ModelCollection<User> users = users();
        deleteUser(1);
        deleteUser(2);
        deleteUser(3);

        users.loadCount("posts");

        assertEquals(1, db.sql().size());
        for (User user : users) {
            assertNull(user.getAttribute("posts_count"));
        }
    }

    @Test
    void nullKeySkipped() {
        List<User> models = new ArrayList<>(users());
        User unsaved = User.QUERY.on(db.db).newModel();
        models.add(unsaved);
        ModelCollection<User> users = collectionOf(models);

        users.loadCount("posts");

        assertEquals(Collections.singletonList("select \"id\", " + COUNT_POSTS + KEYS), db.sql());
        assertNull(unsaved.getAttribute("posts_count"));
        assertEquals(2L, number(users.get(0), "posts_count"));
    }

    @Test
    void allNullKeysNoQuery() {
        User unsaved = User.QUERY.on(db.db).newModel();
        ModelCollection<User> users = collectionOf(Collections.singletonList(unsaved));
        db.clearLog();

        users.loadCount("posts");

        assertTrue(db.sql().isEmpty());
        assertNull(unsaved.getAttribute("posts_count"));
    }

    @Test
    void otherDirtyKept() {
        ModelCollection<User> users = users();
        User ann = users.get(0);
        ann.setAttribute("name", "Anna");

        users.loadCount("posts");

        assertFalse(ann.isDirty("posts_count"));
        assertTrue(ann.isDirty("name"));
        assertEquals("Anna", ann.getAttribute("name"));
    }

    @Test
    void reloadOverwrites() {
        ModelCollection<User> users = users();
        users.loadCount("posts");
        db.seed("posts", Map.of("id", 4, "user_id", 3, "title", "d"));

        users.loadCount("posts");

        assertEquals(1L, number(users.get(2), "posts_count"));
        assertFalse(users.get(2).isDirty());
    }

    @Test
    void existsOverlayMerged() {
        ModelCollection<User> users = users();

        users.loadExists("profile");

        assertEquals(Boolean.TRUE, users.get(0).getAttribute("profile_exists"));
        assertEquals(Boolean.FALSE, users.get(1).getAttribute("profile_exists"));
        assertFalse(users.get(0).isDirty());
    }

    @Test
    void existsMapForm() {
        ModelCollection<User> users = users();

        users.loadExists(one("posts", q -> q.where("title", "c")));

        assertEquals(Boolean.FALSE, users.get(0).getAttribute("posts_exists"));
        assertEquals(Boolean.TRUE, users.get(1).getAttribute("posts_exists"));
    }

    @Test
    void allFunctions() {
        ModelCollection<User> users = users();

        users.loadMax("posts", "id").loadMin("posts", "id").loadSum("posts", "id").loadAvg("posts", "id");

        User ann = users.get(0);
        assertEquals(2L, number(ann, "posts_max_id"));
        assertEquals(1L, number(ann, "posts_min_id"));
        assertEquals(3L, number(ann, "posts_sum_id"));
        assertEquals(1.5, ((Number) ann.getAttribute("posts_avg_id")).doubleValue(), 1e-9);
        assertEquals("select \"id\", (select max(\"posts\".\"id\") " + POSTS_OF_USER + ") as \"posts_max_id\"" + KEYS,
                db.sql().get(0));
        assertEquals(4, db.sql().size());
    }

    @Test
    void mapFormsOfFunctions() {
        Consumer<EloquentBuilder<?>> notA = q -> q.where("title", "<>", "a");
        ModelCollection<User> users = users();

        users.loadMax(one("posts", notA), "id").loadMin(one("posts", notA), "id")
                .loadSum(one("posts", notA), "id").loadAvg(one("posts", notA), "id");

        User ann = users.get(0);
        assertEquals(2L, number(ann, "posts_max_id"));
        assertEquals(2L, number(ann, "posts_min_id"));
        assertEquals(2L, number(ann, "posts_sum_id"));
        assertEquals(2.0, ((Number) ann.getAttribute("posts_avg_id")).doubleValue(), 1e-9);
    }

    @Test
    void aggregateForms() {
        ModelCollection<User> users = users();

        users.loadAggregate("posts", "id", "max")
                .loadAggregate(one("posts as titled", q -> q.where("title", "b")), "id", "count")
                .loadAggregate("profile", "bio");

        User ann = users.get(0);
        assertEquals(2L, number(ann, "posts_max_id"));
        assertEquals(1L, number(ann, "titled"));
        assertEquals("hi", ann.getAttribute("profile_bio"));
        assertNull(users.get(1).getAttribute("profile_bio"));
    }

    @Test
    void asAlias() {
        ModelCollection<User> users = users();

        users.loadCount("posts as total");

        assertEquals(2L, number(users.get(0), "total"));
        assertNull(users.get(0).getAttribute("posts_count"));
    }

    @Test
    void closureApplied() {
        ModelCollection<User> users = users();

        users.loadCount(one("posts", q -> q.where("title", "b")));

        assertEquals(1L, number(users.get(0), "posts_count"));
        assertEquals(0L, number(users.get(1), "posts_count"));
        assertEquals(Collections.singletonList("b"), db.queries().get(0).bindings());
    }

    @Test
    void unknownRelation() {
        ModelCollection<User> users = users();

        assertThrows(RelationNotFoundException.class, () -> users.loadCount("postz"));
        assertThrows(RelationNotFoundException.class, () -> users.loadCount("posts.comments"));
        assertTrue(db.sql().isEmpty());
    }

    @Test
    void nullArgumentsRejected() {
        ModelCollection<User> users = users();

        assertThrows(NullPointerException.class, () -> users.loadCount((String) null));
        assertThrows(NullPointerException.class, () -> users.loadMax((String) null, "id"));
        assertThrows(NullPointerException.class,
                () -> users.loadMax((Map<String, Consumer<EloquentBuilder<?>>>) null, "id"));
        assertThrows(NullPointerException.class, () -> users.loadMax("posts", null));
        assertThrows(NullPointerException.class,
                () -> users.loadAggregate((Map<String, Consumer<EloquentBuilder<?>>>) null, "id", "max"));
        assertTrue(db.sql().isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mixedClassesRejected() {
        List mixed = new ArrayList<>(users());
        mixed.add(TextUser.QUERY.on(db.db).newModel());
        ModelCollection<Model> collection = (ModelCollection<Model>) Access.eloquent()
                .newCollection((EloquentBuilder) User.QUERY.on(db.db).query(), mixed);

        assertThrows(IllegalArgumentException.class, () -> collection.loadCount("posts"));
    }

    @Test
    void selfRelation() {
        SQLiteSchema.of(db.real).create("categories", t -> {
            t.id();
            t.integer("parent_id").nullable();
        });
        db.seed("categories", Map.of("id", 1));
        db.seed("categories", Map.of("id", 2, "parent_id", 1));
        db.seed("categories", Map.of("id", 3, "parent_id", 1));
        ModelCollection<Category> categories = Category.QUERY.on(db.db).query().orderBy("id").get();
        db.clearLog();

        categories.loadCount("children");

        assertEquals(Collections.singletonList("select \"id\", (select count(*) from \"categories\" as"
                + " \"laravel_reserved_0\" where \"categories\".\"id\" = \"laravel_reserved_0\".\"parent_id\")"
                + " as \"children_count\" from \"categories\" where \"categories\".\"id\" in (1, 2, 3)"), db.sql());
        assertEquals(2L, number(categories.get(0), "children_count"));
        assertEquals(0L, number(categories.get(1), "children_count"));
    }

    @Test
    void noEagerLoadsInAggregateQuery() {
        ModelCollection<CountingUser> users = CountingUser.QUERY.on(db.db).with("posts").orderBy("id").get();
        Object annPosts = users.get(0).getRelation("posts");
        CountingUser.RETRIEVED.set(0);
        db.clearLog();

        users.loadCount("posts");

        assertEquals(1, db.sql().size());
        assertEquals(0, CountingUser.RETRIEVED.get());
        assertSame(annPosts, users.get(0).getRelation("posts"));
        assertEquals(2L, number(users.get(0), "posts_count"));
    }
}

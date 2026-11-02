package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The eager loader: path checks, nesting, constraints and chunking, called through the bridge,
 * then through the builder's {@code with} and the collection's {@code load}.
 */
class EagerLoadTest {

    /** Above SQLite's bound chunk of 900, so bound keys need two queries. */
    private static final int MANY = 1_000;

    private static final Consumer<EloquentBuilder<?>> NONE = q -> {
    };

    private final RelationAccessImpl access = new RelationAccessImpl();

    private RelationDb db;

    /** A user over the users table whose key is declared text, so eager keys are bound. */
    static final class TextUser extends Model {
        static final ModelQuery<TextUser> QUERY = ModelQuery.of(TextUser.class, TextUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected String keyType() {
            return "string";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TextUser.class).add("posts", TextUser::posts);
        }

        public HasMany<Post> posts() {
            return hasMany(Post.class, "user_id");
        }
    }

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        db.seed("posts", Map.of("id", 3, "user_id", 3, "title", "b"));
        db.seed("comments", Map.of("id", 1, "post_id", 1, "body", "x"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private ModelCollection<User> users() {
        ModelCollection<User> users = User.QUERY.on(db.db).all();
        db.clearLog();
        return users;
    }

    private static Map<String, Consumer<EloquentBuilder<?>>> paths(String... names) {
        Map<String, Consumer<EloquentBuilder<?>>> map = new LinkedHashMap<>();
        for (String name : names) {
            map.put(name, NONE);
        }
        return map;
    }

    private List<TextUser> manyTextUsers() {
        List<TextUser> users = new ArrayList<>();
        for (int id = 1; id <= MANY; id++) {
            TextUser user = TextUser.QUERY.on(db.db).newModel();
            user.setAttribute("id", id);
            users.add(user);
        }
        return users;
    }

    @Test
    void loadsAndMatchesHasMany() {
        ModelCollection<User> users = users();

        access.load(users, paths("posts"));

        assertEquals(2, users.get(0).relatedMany("posts").size());
        assertEquals(0, users.get(1).relatedMany("posts").size());
        assertEquals(1, db.sql().size());
    }

    @Test
    void emptyMiddleLevelStopsNesting() {
        List<User> bobOnly = Collections.singletonList(users().get(1));

        access.load(bobOnly, paths("posts", "posts.comments"));

        assertEquals(1, db.sql().size());
        assertTrue(db.sql().get(0).startsWith("select * from \"posts\""), db.sql().get(0));
        assertTrue(bobOnly.get(0).relatedMany("posts").isEmpty());
    }

    @Test
    void nestedPathLoadsEachLevelOnce() {
        ModelCollection<User> users = users();

        access.load(users, paths("posts", "posts.comments"));

        assertEquals(2, db.sql().size());
        Post first = users.get(0).<Post>relatedMany("posts").get(0);
        assertEquals(1, first.relatedMany("comments").size());
    }

    @Test
    void constrainedLimitRefused() {
        Map<String, Consumer<EloquentBuilder<?>>> eager = Collections.singletonMap("posts", q -> q.limit(3));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> access.load(users(), eager));

        assertTrue(e.getMessage().contains("[posts]"), e.getMessage());
    }

    @Test
    void constrainedOffsetRefused() {
        Map<String, Consumer<EloquentBuilder<?>>> eager = Collections.singletonMap("posts", q -> q.offset(5));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> access.load(users(), eager));

        assertTrue(e.getMessage().contains("[posts]"), e.getMessage());
    }

    @Test
    void unknownRelationThrows() {
        ModelCollection<User> users = users();

        RelationNotFoundException e = assertThrows(RelationNotFoundException.class,
                () -> access.load(users, paths("postz")));

        assertTrue(e.getMessage().contains("posts") && e.getMessage().contains("profile"), e.getMessage());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void unknownNestedRelationThrows() {
        ModelCollection<User> users = users();

        RelationNotFoundException e = assertThrows(RelationNotFoundException.class,
                () -> access.load(users, paths("posts", "posts.commentz")));

        assertTrue(e.getMessage().contains(Post.class.getName()) || e.getMessage().contains("Post"), e.getMessage());
        assertTrue(e.getMessage().contains("commentz"), e.getMessage());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void selectConstraintKeepsKey() {
        ModelCollection<User> users = users();
        Map<String, Consumer<EloquentBuilder<?>>> eager = Collections.singletonMap("posts", q -> q.select("id", "title"));

        access.load(users, eager);

        assertEquals(2, users.get(0).relatedMany("posts").size());
        assertTrue(db.sql().get(0).contains("\"posts\".\"user_id\" from"), db.sql().get(0));
    }

    @Test
    void probeShrinksBoundChunk() {
        List<TextUser> users = manyTextUsers();
        Map<String, Consumer<EloquentBuilder<?>>> eager = Collections.singletonMap("posts", q -> q.where("title", "b"));

        access.load(users, eager);

        List<RelationDb.Query> queries = db.queries();
        assertEquals(2, queries.size());
        assertEquals(900, queries.get(0).bindings().size());
        assertEquals(MANY - 899 + 1, queries.get(1).bindings().size());
    }

    @Test
    void constraintRunsPerChunkPlusProbe() {
        List<TextUser> users = manyTextUsers();
        AtomicInteger runs = new AtomicInteger();
        Map<String, Consumer<EloquentBuilder<?>>> eager = Collections.singletonMap("posts", q -> runs.incrementAndGet());

        access.load(users, eager);

        assertEquals(2, db.sql().size());
        assertEquals(3, runs.get());
    }

    @Test
    void nestedLoadedOnceAcrossChunks() {
        List<TextUser> users = manyTextUsers();

        access.load(users, paths("posts", "posts.comments"));

        assertEquals(3, db.sql().size());
        assertTrue(db.sql().get(2).startsWith("select * from \"comments\""), db.sql().get(2));
        assertEquals(1, users.get(0).<Post>relatedMany("posts").get(0).relatedMany("comments").size());
    }

    @Test
    void withInsideConstraintRunsOnce() {
        List<TextUser> users = manyTextUsers();
        Map<String, Consumer<EloquentBuilder<?>>> eager = Collections.singletonMap("posts", q -> q.with("comments"));

        access.load(users, eager);

        assertEquals(3, db.sql().size());
        Post first = users.get(0).<Post>relatedMany("posts").get(0);
        assertTrue(first.relationLoaded("comments"));
    }

    @Test
    void orInConstraintDocumentedParity() {
        ModelCollection<User> users = users();
        Map<String, Consumer<EloquentBuilder<?>>> eager = Collections.singletonMap("posts",
                q -> q.where("title", "a").orWhere("title", "b"));

        access.load(users, eager);

        // Laravel order: the key filter first, so an ungrouped or reads rows of other parents.
        assertTrue(db.sql().get(0).endsWith("in (1, 2) and \"title\" = ? or \"title\" = ?"), db.sql().get(0));
        assertEquals(2, users.get(0).relatedMany("posts").size());
    }

    @Test
    void loadMissingSkipsLoadedRelations() {
        ModelCollection<User> users = users();
        access.load(users, paths("posts"));
        db.clearLog();

        access.loadMissing(users, paths("posts", "posts.comments"));

        assertEquals(1, db.sql().size());
        assertTrue(db.sql().get(0).startsWith("select * from \"comments\""), db.sql().get(0));
    }

    @Test
    void mixedClassesRefused() {
        List<Model> mixed = new ArrayList<>(users());
        mixed.add(TextUser.QUERY.on(db.db).newModel());

        assertThrows(IllegalArgumentException.class, () -> access.load(mixed, paths("posts")));
    }

    @Test
    void resolveNamesTheRelation() {
        Relation<?, ?> relation = access.resolve(users().get(0), "posts");

        assertEquals("posts", relation.getRelationName());
    }

    // ---- through the builder and the collection

    private long postQueries() {
        long count = 0;
        for (String sql : db.sql()) {
            if (sql.startsWith("select * from \"posts\"")) {
                count++;
            }
        }
        return count;
    }

    @Test
    void emptyResultRunsNoRelationQuery() {
        db.clearLog();

        ModelCollection<User> none = User.QUERY.on(db.db).with("posts").where("id", 99).get();

        assertTrue(none.isEmpty());
        assertEquals(1, db.sql().size());
    }

    @Test
    void nestedRunsThreeQueries() {
        db.clearLog();

        ModelCollection<User> users = User.QUERY.on(db.db).with("posts.comments").get();

        assertEquals(3, db.sql().size());
        List<Post> posts = users.get(0).relatedMany("posts");
        assertEquals(2, posts.size());
        assertEquals(1, posts.get(0).relatedMany("comments").size());
        assertEquals(0, posts.get(1).relatedMany("comments").size());
        assertTrue(users.get(1).relatedMany("posts").isEmpty());
        assertEquals(3, db.sql().size());

        db.clearLog();
        User.QUERY.on(db.db).with("posts.comments").where("id", 99).get();
        assertEquals(1, db.sql().size());
    }

    @Test
    void builderEagerLoadState() {
        EloquentBuilder<User> q = User.QUERY.on(db.db).with("posts.comments", "profile").without("profile");

        assertEquals(Arrays.asList("posts", "posts.comments"), new ArrayList<>(q.getEagerLoads().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> q.getEagerLoads().clear());
        assertEquals(Collections.singletonList("profile"), new ArrayList<>(q.withOnly("profile").getEagerLoads().keySet()));
    }

    @Test
    void constrainedWithFiltersRelation() {
        ModelCollection<User> users = User.QUERY.on(db.db).with("posts", q -> q.where("title", "b")).get();

        assertEquals(1, users.get(0).relatedMany("posts").size());
    }

    @Test
    void copyCarriesEagerLoads() {
        User ann = User.QUERY.on(db.db).with("posts").firstOrNew(Collections.singletonMap("name", "Ann"));

        assertTrue(ann.relationLoaded("posts"));
    }

    @Test
    void cursorWithEagerRefused() {
        EloquentBuilder<User> q = User.QUERY.on(db.db).with("posts");
        db.clearLog();

        IllegalStateException e = assertThrows(IllegalStateException.class, q::cursor);

        assertTrue(e.getMessage().contains("lazy()"), e.getMessage());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void chunkLoadsPerChunk() {
        db.clearLog();
        List<Boolean> loaded = new ArrayList<>();

        User.QUERY.on(db.db).with("posts").chunk(1, chunk -> loaded.add(chunk.get(0).relationLoaded("posts")));

        assertEquals(Arrays.asList(true, true), loaded);
        assertEquals(2, postQueries());

        db.clearLog();
        loaded.clear();
        User.QUERY.on(db.db).with("posts").chunkById(1, chunk -> loaded.add(chunk.get(0).relationLoaded("posts")));
        assertEquals(Arrays.asList(true, true), loaded);
        assertEquals(2, postQueries());
    }

    @Test
    void lazyBuffersAndLoads() {
        db.clearLog();
        List<String> seen = new ArrayList<>();

        for (User user : User.QUERY.on(db.db).with("posts").lazy(1)) {
            seen.add(user.getAttribute("name") + ":" + user.relatedMany("posts").size());
        }

        assertEquals(Arrays.asList("Ann:2", "Bob:0"), seen);
        assertEquals(2, postQueries());

        db.clearLog();
        int count = 0;
        for (User user : User.QUERY.on(db.db).with("posts").lazyById(10)) {
            assertTrue(user.relationLoaded("posts"));
            count++;
        }
        assertEquals(2, count);
        assertEquals(1, postQueries());
    }

    @Test
    void findAndPaginateLoad() {
        ModelQuery<User> users = User.QUERY.on(db.db);

        assertTrue(users.with("posts").find(1).get().relationLoaded("posts"));
        assertTrue(users.with("posts").findOrFail(1).relationLoaded("posts"));
        assertTrue(users.with("posts").first().get().relationLoaded("posts"));
        assertTrue(users.with("posts").paginate(1, 2).items().get(0).relationLoaded("posts"));
        assertTrue(users.with("posts").simplePaginate(1, 1).items().get(0).relationLoaded("posts"));
        assertTrue(users.with("posts").cursorPaginate(1).items().get(0).relationLoaded("posts"));
    }

    @Test
    void loadOnEmptyCollection() {
        ModelCollection<User> empty = User.QUERY.on(db.db).where("id", 99).get();
        db.clearLog();

        assertSame(empty, empty.load("posts"));
        assertSame(empty, empty.loadMissing("posts"));
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void collectionLoadFillsEveryModel() {
        ModelCollection<User> users = users();

        assertSame(users, users.load("posts", q -> q.orderByDesc("id")));

        assertEquals(2L, ((Number) users.get(0).<Post>relatedMany("posts").get(0).getKey()).longValue());
        assertEquals(1, db.sql().size());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mixedCollectionRejected() {
        List mixed = new ArrayList<>(users());
        mixed.add(TextUser.QUERY.on(db.db).newModel());
        ModelCollection<Model> collection = (ModelCollection<Model>) Access.eloquent()
                .newCollection((EloquentBuilder) User.QUERY.on(db.db).query(), mixed);

        assertThrows(IllegalArgumentException.class, () -> collection.load("posts"));
    }
}

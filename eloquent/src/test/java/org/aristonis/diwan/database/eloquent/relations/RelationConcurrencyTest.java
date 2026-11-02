package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelationConcurrencyTest {

    private static final int THREADS = 8;
    private static final int USERS = 100;
    private static final int ROUNDS = 20;
    private static final long LAZY_USER = 7;

    /** A class no other test touches, so its registry is checked for the first time here. */
    static final class RaceUser extends Model {
        static final ModelQuery<RaceUser> QUERY = ModelQuery.of(RaceUser.class, RaceUser::new);
        static final AtomicInteger BUILDS = new AtomicInteger();

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(RaceUser.class).add("posts", RaceUser::posts);
        }

        public HasMany<Post> posts() {
            BUILDS.incrementAndGet();
            return hasMany(Post.class, "user_id");
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void registryInitRace() throws Exception {
        ModelQuery<RaceUser> users = RaceUser.QUERY.on(db.db);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> names = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                RaceUser user = users.newModel();
                names.add(pool.submit(() -> {
                    start.await();
                    return user.relation("posts").getRelationName();
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> name : names) {
                results.add(name.get(10, TimeUnit.SECONDS));
            }

            assertEquals(Collections.nCopies(THREADS, "posts"), results);
            // Each thread builds its own relation; the check adds one build per racing checker.
            int builds = RaceUser.BUILDS.get();
            assertTrue(builds >= THREADS + 1 && builds <= 2 * THREADS, "builds " + builds);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void noConstraintLeak() throws Exception {
        for (int id = 1; id <= USERS; id++) {
            db.seed("users", Map.of("id", id, "name", "u" + id));
            db.seed("posts", Map.of("id", id, "user_id", id, "title", "p" + id));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> eager = pool.submit(rounds(start, () -> {
                ModelCollection<User> users = User.QUERY.on(db.db).with("posts").get();
                boolean ok = users.size() == USERS;
                for (User user : users) {
                    List<Post> posts = user.relatedMany("posts");
                    ok &= posts.size() == 1 && sameKey(posts.get(0).getAttribute("user_id"), user.getKey());
                }
                return ok;
            }));
            Future<Boolean> lazy = pool.submit(rounds(start, () -> {
                User seven = User.QUERY.on(db.db).findOrFail(LAZY_USER);
                List<Post> posts = seven.relatedMany("posts");
                return posts.size() == 1 && sameKey(posts.get(0).getAttribute("user_id"), LAZY_USER);
            }));
            start.countDown();

            assertTrue(eager.get(30, TimeUnit.SECONDS), "eager loads saw another thread's constraint");
            assertTrue(lazy.get(30, TimeUnit.SECONDS), "lazy load saw another thread's constraint");
            for (RelationDb.Query query : db.queries()) {
                if (query.sql().contains("\"posts\".\"user_id\" = ?")) {
                    assertEquals(Collections.<Object>singletonList(LAZY_USER), longs(query.bindings()), query.sql());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<Boolean> rounds(CountDownLatch start, Callable<Boolean> round) {
        return () -> {
            start.await();
            boolean ok = true;
            for (int i = 0; i < ROUNDS; i++) {
                ok &= round.call();
            }
            return ok;
        };
    }

    private static boolean sameKey(Object a, Object b) {
        return a instanceof Number && b instanceof Number && ((Number) a).longValue() == ((Number) b).longValue();
    }

    private static List<Object> longs(List<Object> values) {
        List<Object> out = new ArrayList<>();
        for (Object value : values) {
            out.add(value instanceof Number ? (Object) ((Number) value).longValue() : value);
        }
        return out;
    }
}

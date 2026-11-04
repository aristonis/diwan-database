package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.TouchScope;
import org.aristonis.diwan.database.eloquent.relations.WithoutTouchingTest.TouchComment;
import org.aristonis.diwan.database.eloquent.relations.WithoutTouchingTest.TouchPost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The {@code withoutTouching} scope is per thread and gone after the call. */
class TouchConcurrencyTest {

    private static final long WAIT_SECONDS = 10;

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        WithoutTouchingTest.createTables(db.real);
        WithoutTouchingTest.seed(db.real);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private boolean saveDirty(int id) {
        TouchComment comment = TouchComment.QUERY.on(db.db).findOrFail(id);
        comment.setAttribute("body", "changed by " + Thread.currentThread().getName());
        return comment.save();
    }

    @Test
    void otherThreadStillTouches() throws Exception {
        CountDownLatch inScope = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> Model.withoutTouching(TouchPost.class, () -> {
                inScope.countDown();
                await(otherDone);
                return saveDirty(1);
            }));
            Future<Boolean> b = pool.submit(() -> {
                await(inScope);
                assertFalse(TouchScope.isActive());
                boolean saved = saveDirty(2);
                otherDone.countDown();
                return saved;
            });

            assertTrue(b.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(a.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertFalse(WithoutTouchingTest.touched(db.real, "wt_posts", 1));
        assertTrue(WithoutTouchingTest.touched(db.real, "wt_posts", 2));
    }

    @Test
    void threadStartedInsideTheScopeTouches() throws Exception {
        boolean[] saved = {false};

        Model.withoutTouching(TouchPost.class, () -> {
            Thread child = new Thread(() -> saved[0] = saveDirty(2));
            child.start();
            join(child);
            saveDirty(1);
        });

        assertTrue(saved[0]);
        assertFalse(WithoutTouchingTest.touched(db.real, "wt_posts", 1));
        assertTrue(WithoutTouchingTest.touched(db.real, "wt_posts", 2));
    }

    @Test
    void noLeakToNextTask() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> Model.withoutTouching(TouchPost.class, () -> saveDirty(1)))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);
            Future<Boolean> next = pool.submit(() -> {
                assertFalse(TouchScope.isActive());
                return saveDirty(2);
            });

            assertTrue(next.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertFalse(WithoutTouchingTest.touched(db.real, "wt_posts", 1));
        assertTrue(WithoutTouchingTest.touched(db.real, "wt_posts", 2));
    }

    @Test
    void noLeakAfterAFailedTask() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> failed = pool.submit(() -> Model.withoutTouching(TouchPost.class, (Supplier<Object>) () -> {
                throw new IllegalStateException("boom");
            }));
            assertTrue(failedWith(failed, IllegalStateException.class));
            Future<Boolean> next = pool.submit(() -> {
                assertFalse(TouchScope.isActive());
                return saveDirty(2);
            });

            assertTrue(next.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertTrue(WithoutTouchingTest.touched(db.real, "wt_posts", 2));
    }

    private static boolean failedWith(Future<?> future, Class<? extends Throwable> type) throws Exception {
        try {
            future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return type.isInstance(e.getCause());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

package org.aristonis.diwan.database;

import org.aristonis.diwan.database.event.Registration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Listeners can be added and removed while other threads run statements. */
class ListenerConcurrencyTest {

    private static final int STATEMENTS = 2000;
    private static final int CHANGES = 2000;
    private static final long WAIT_SECONDS = 30;

    private final FakeDriver driver = new FakeDriver();
    private final Connection db = Connection.builder().name("main").driver(driver).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private static void await(Future<?> future) throws Exception {
        future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void registeringAndRemovingListenersWhileAnotherThreadRunsQueriesRaisesNoException() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        Future<?> runner = pool.submit(() -> {
            go.await();
            for (int i = 0; i < STATEMENTS; i++) {
                db.select("select " + i);
            }
            return null;
        });
        Future<?> changer = pool.submit(() -> {
            go.await();
            for (int i = 0; i < CHANGES; i++) {
                Registration registration = db.events().listen(e -> { });
                registration.remove();
            }
            return null;
        });

        go.countDown();

        await(runner);
        await(changer);
        assertEquals(0, driver.openSessions());
    }

    @Test
    void aListenerRemovingItselfWhileItIsCalledRaisesNoException() {
        AtomicReference<Registration> own = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        own.set(db.events().listen(e -> {
            calls.incrementAndGet();
            own.get().remove();
        }));

        db.select("select 1");
        db.select("select 2");

        assertEquals(1, calls.get());
    }

    @Test
    void aListenerAddingAnotherListenerWhileItIsCalledRaisesNoException() {
        List<String> seen = new ArrayList<>();
        db.events().listen(e -> db.events().listen(next -> seen.add(next.sql())));

        db.select("select 1");

        assertTrue(seen.size() <= 1);
    }

    @Test
    void twoThreadsRunningQueriesTogetherEachHearEveryStatementOnce() throws Exception {
        RecordingListener listener = new RecordingListener();
        db.events().addListener(listener);
        db.events().enableQueryLog();
        CountDownLatch go = new CountDownLatch(1);
        Future<?> first = pool.submit(() -> run(go, "a"));
        Future<?> second = pool.submit(() -> run(go, "b"));

        go.countDown();

        await(first);
        await(second);
        assertEquals(2 * 200, listener.executed().size());
        assertEquals(2 * 200, db.events().getQueryLog().size());
    }

    private Object run(CountDownLatch go, String tag) throws Exception {
        go.await();
        for (int i = 0; i < 200; i++) {
            db.select("select '" + tag + "' " + i);
        }
        return null;
    }

    @Test
    void theDispatchGuardOfOneThreadDoesNotSilenceAnother() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> seen = java.util.Collections.synchronizedList(new ArrayList<String>());
        db.events().listen(e -> {
            seen.add(e.sql());
            if (e.sql().equals("select blocker")) {
                inside.countDown();
                try {
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Future<?> blocker = pool.submit(() -> db.select("select blocker"));
        assertTrue(inside.await(WAIT_SECONDS, TimeUnit.SECONDS));

        db.select("select other");
        release.countDown();

        await(blocker);
        assertTrue(seen.contains("select other"), seen.toString());
    }
}

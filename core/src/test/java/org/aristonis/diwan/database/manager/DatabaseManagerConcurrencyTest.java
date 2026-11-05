package org.aristonis.diwan.database.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.Connection;
import org.junit.jupiter.api.Test;

class DatabaseManagerConcurrencyTest {

    private static final int THREADS = 16;
    private static final int WAIT_SECONDS = 10;

    @Test
    void sixteenThreadsFirstUsingTheSameNameBuildItOnce() throws Exception {
        FakeConnector connector = new FakeConnector("fake").beforeConnect(config -> pause());
        DatabaseManager manager = ManagerFixtures.manager(connector, "main");
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Connection>> results = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return manager.connection("main");
                }));
            }
            start.countDown();

            Connection first = results.get(0).get(WAIT_SECONDS, TimeUnit.SECONDS);
            for (Future<Connection> result : results) {
                assertSame(first, result.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            assertEquals(1, connector.connects());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSlowBuildOfOneNameDoesNotBlockAnotherName() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        FakeConnector connector = new FakeConnector("fake").beforeConnect(config -> {
            if (config.name().equals("slow")) {
                slowStarted.countDown();
                await(releaseSlow);
            }
        });
        DatabaseManager manager = ManagerFixtures.manager(connector, "fast", "slow");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Connection> slow = pool.submit(() -> manager.connection("slow"));
            assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS),
                    () -> assertNotNull(manager.connection("fast")));
            releaseSlow.countDown();

            assertNotNull(slow.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            releaseSlow.countDown();
            pool.shutdownNow();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    @Test
    void aConnectionHeldAcrossAPurgeStaysUsableAndTheNextOneIsNew() {
        FakeConnector connector = new FakeConnector("fake");
        DatabaseManager manager = ManagerFixtures.manager(connector, "main");
        Connection before = manager.connection("main");

        manager.purge("main");
        Connection after = manager.connection("main");

        assertNotSame(before, after);
        assertEquals(1, connector.closed().size());
        assertSame(before, connector.closed().get(0));
        assertEquals(2, connector.connects());
        manager.purge("main");
        assertEquals(2, connector.closed().size());
    }

    @Test
    void aThreadHoldingAConnectionWhileAnotherPurgesSeesTheOldResourceClosedOnce() throws Exception {
        FakeConnector connector = new FakeConnector("fake");
        DatabaseManager manager = ManagerFixtures.manager(connector, "main");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch purged = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Connection> holder = pool.submit(() -> {
                Connection mine = manager.connection("main");
                held.countDown();
                await(purged);
                return mine;
            });
            assertTrue(held.await(WAIT_SECONDS, TimeUnit.SECONDS));

            manager.purge("main");
            Connection fresh = manager.connection("main");
            purged.countDown();

            Connection old = holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotSame(old, fresh);
            assertEquals(1, connector.closed().size());
            assertSame(old, connector.closed().get(0));
        } finally {
            purged.countDown();
            pool.shutdownNow();
        }
    }
}

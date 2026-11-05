package org.aristonis.diwan.database.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class UsingConnectionTest {

    private static final int WAIT_SECONDS = 10;

    private final DatabaseManager manager = ManagerFixtures.manager(new FakeConnector("fake"), "a", "b", "c");

    @Test
    void overridesTheDefaultForTheWorkAndRestoresIt() {
        Supplier<String> work = manager::getDefaultConnection;

        String inside = manager.usingConnection("b", work);

        assertEquals("b", inside);
        assertEquals("a", manager.getDefaultConnection());
    }

    @Test
    void connectionInsideTheWorkIsTheOverriddenOne() {
        Supplier<Object> work = () -> manager.connection();

        Object inside = manager.usingConnection("b", work);

        assertSame(manager.connection("b"), inside);
    }

    @Test
    void restoresTheDefaultWhenTheWorkThrows() {
        Runnable failing = () -> {
            throw new IllegalStateException("boom");
        };

        assertThrows(IllegalStateException.class, () -> manager.usingConnection("b", failing));

        assertEquals("a", manager.getDefaultConnection());
    }

    @Test
    void nestsAndUnwindsInOrder() {
        Supplier<String> inner = manager::getDefaultConnection;
        Supplier<String> outer = () -> manager.getDefaultConnection() + manager.usingConnection("c", inner)
                + manager.getDefaultConnection();

        assertEquals("bcb", manager.usingConnection("b", outer));
        assertEquals("a", manager.getDefaultConnection());
    }

    @Test
    void anOverrideOnOneThreadDoesNotChangeAnotherThread() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Runnable hold = () -> {
                inside.countDown();
                try {
                    finish.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Future<?> other = pool.submit(() -> manager.usingConnection("b", hold));
            inside.await(WAIT_SECONDS, TimeUnit.SECONDS);

            assertEquals("a", manager.getDefaultConnection());

            finish.countDown();
            other.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void anUnknownNameFailsBeforeTheWorkRuns() {
        boolean[] ran = new boolean[1];
        Runnable work = () -> ran[0] = true;

        assertThrows(IllegalArgumentException.class, () -> manager.usingConnection("zzz", work));

        assertEquals(false, ran[0]);
    }

    @Test
    void theOverrideIsGoneAfterTheLastWorkEnds() {
        Supplier<String> work = manager::getDefaultConnection;
        manager.usingConnection("b", work);
        manager.setDefaultConnection("c");

        assertEquals("c", manager.getDefaultConnection());
    }
}

package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Listeners registered or forgotten while other threads fire: no failure and no lost registration. */
class ModelEventConcurrencyTest {

    private static final int REGISTRARS = 8;
    private static final int FIRERS = 4;
    private static final int ROUNDS = 300;
    private static final long TIMEOUT_SECONDS = 30L;

    @AfterEach
    void close() {
        EventFixtures.forgetAll();
    }

    private static void awaitAll(List<Future<?>> tasks) throws Exception {
        for (Future<?> task : tasks) {
            task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void registrationWhileOthersFireLosesNothingAndFailsNothing() throws Exception {
        AtomicInteger base = new AtomicInteger();
        AtomicInteger added = new AtomicInteger();
        Article.QUERY.events().saved(m -> base.incrementAndGet());
        ExecutorService pool = Executors.newFixedThreadPool(REGISTRARS + FIRERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < REGISTRARS; i++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    Article.QUERY.events().saved(m -> added.incrementAndGet());
                    return null;
                }));
            }
            for (int i = 0; i < FIRERS; i++) {
                tasks.add(pool.submit(() -> {
                    Article model = Article.QUERY.newModel();
                    start.await();
                    for (int round = 0; round < ROUNDS; round++) {
                        model.fire(ModelEvent.SAVED);
                    }
                    return null;
                }));
            }
            start.countDown();

            awaitAll(tasks);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(FIRERS * ROUNDS, base.get());
        int before = added.get();
        Article.QUERY.newModel().fire(ModelEvent.SAVED);
        assertEquals(before + REGISTRARS, added.get(), "every registration survived the race");
    }

    @Test
    void haltingFireWhileOthersRegisterStillAnswersTrueOrFalseWithoutFailing() throws Exception {
        Article.QUERY.events().saving(m -> true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            tasks.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < ROUNDS; i++) {
                    Article.QUERY.events().saving(m -> true);
                }
                return null;
            }));
            tasks.add(pool.submit(() -> {
                Article model = Article.QUERY.newModel();
                start.await();
                for (int i = 0; i < ROUNDS; i++) {
                    assertTrue(model.fireHalting(ModelEvent.SAVING));
                }
                return null;
            }));
            start.countDown();

            awaitAll(tasks);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(Article.QUERY.events().hasListeners());
    }

    @Test
    void forgetWhileAnotherThreadFiresSilencesLaterFires() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch fired = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        Article.QUERY.events().saved(m -> {
            calls.incrementAndGet();
            fired.countDown();
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> firer = pool.submit(() -> {
                Article model = Article.QUERY.newModel();
                while (!stop.get()) {
                    model.fire(ModelEvent.SAVED);
                }
                return null;
            });
            assertTrue(fired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            Article.QUERY.events().forget();
            stop.set(true);
            firer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }

        int settled = calls.get();
        Article.QUERY.newModel().fire(ModelEvent.SAVED);
        assertEquals(settled, calls.get());
        assertFalse(Article.QUERY.events().hasListeners());
    }

    @Test
    void eachThreadMutesOnlyItself() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Article.QUERY.events().saved(m -> calls.incrementAndGet());
        ExecutorService pool = Executors.newFixedThreadPool(FIRERS);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < FIRERS; i++) {
                tasks.add(pool.submit(() -> {
                    Article model = Article.QUERY.newModel();
                    Model.withoutEvents(() -> {
                        model.fire(ModelEvent.SAVED);
                    });
                    return null;
                }));
            }

            awaitAll(tasks);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, calls.get());
        Article.QUERY.newModel().fire(ModelEvent.SAVED);
        assertEquals(1, calls.get());
    }
}

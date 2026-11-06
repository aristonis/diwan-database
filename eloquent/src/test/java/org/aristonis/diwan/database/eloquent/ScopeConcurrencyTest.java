package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.ScopeFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Scopes registered by several threads while others build queries: no failure, every scope kept,
 * and every builder sees a prefix of the final registration order.
 */
class ScopeConcurrencyTest {

    private static final int REGISTRARS = 16;
    private static final int BUILDERS = 4;
    private static final int BUILD_ROUNDS = 200;
    private static final long TIMEOUT_SECONDS = 30L;
    private static final String PREFIX = "concurrent-";

    private ScopeFixtures fixtures;

    /** The registry in registration order, and the scope names each builder saw. */
    private static final class Outcome {
        private final List<String> finalOrder;
        private final List<List<String>> builderSnapshots;

        Outcome(List<String> finalOrder, List<List<String>> builderSnapshots) {
            this.finalOrder = finalOrder;
            this.builderSnapshots = builderSnapshots;
        }
    }

    @BeforeEach
    void open() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures = ScopeFixtures.open();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures.close();
    }

    private static List<String> expectedNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < REGISTRARS; i++) {
            names.add(PREFIX + i);
        }
        return names;
    }

    private Outcome race() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REGISTRARS + BUILDERS);
        CountDownLatch start = new CountDownLatch(1);
        Queue<List<String>> snapshots = new ConcurrentLinkedQueue<>();
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (String name : expectedNames()) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    Tag.QUERY.addGlobalScope(name, (EloquentBuilder<?> builder) -> {
                        // Adds nothing: only the registration matters.
                    });
                    return null;
                }));
            }
            for (int i = 0; i < BUILDERS; i++) {
                tasks.add(pool.submit(() -> buildRounds(start, snapshots)));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return new Outcome(new ArrayList<>(Tag.QUERY.getGlobalScopes().keySet()), new ArrayList<>(snapshots));
    }

    private Void buildRounds(CountDownLatch start, Queue<List<String>> snapshots) throws InterruptedException {
        start.await();
        for (int round = 0; round < BUILD_ROUNDS; round++) {
            snapshots.add(fixtures.tags().query().withoutGlobalScopes().removedScopes());
        }
        return null;
    }

    @Test
    void registeringWhileBuildingNeverThrowsAndKeepsEveryScope() throws Exception {
        Outcome outcome = race();

        assertEquals(REGISTRARS, outcome.finalOrder.size());
        assertEquals(new HashSet<>(expectedNames()), new HashSet<>(outcome.finalOrder));
        assertEquals(BUILDERS * BUILD_ROUNDS, outcome.builderSnapshots.size());
    }

    @Test
    void builderSnapshotsArePrefixesOfTheFinalRegistrationOrder() throws Exception {
        Outcome outcome = race();

        for (List<String> seen : outcome.builderSnapshots) {
            assertTrue(seen.size() <= outcome.finalOrder.size(), "a builder cannot see more than was registered");
            assertEquals(outcome.finalOrder.subList(0, seen.size()), seen);
        }
    }
}

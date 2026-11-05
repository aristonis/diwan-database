package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** An observer that opts in with {@code afterCommit()} defers its non-halting methods and no others. */
class ModelAfterCommitObserverTest {

    private EventFixtures fx;

    @BeforeEach
    void open() throws SQLException {
        fx = EventFixtures.open();
        fx.seed();
    }

    @AfterEach
    void close() throws SQLException {
        EventFixtures.forgetAll();
        fx.close();
    }

    private QueryFactory inTransaction(Connection tx) {
        return QueryFactory.of(tx, fx.db.grammar(), fx.db.processor());
    }

    @Test
    void optedInObserverDefersNonHaltingMethodsAndKeepsHaltingOnesInTransaction() {
        Trace trace = new Trace(true);
        Article.QUERY.events().observe(trace);

        fx.real.transaction(tx -> {
            Article.QUERY.on(inTransaction(tx)).make(Map.of("title", "Watched")).save();
            assertEquals(Arrays.asList("saving", "creating"), trace.seen());
            return null;
        });

        assertEquals(Arrays.asList("saving", "creating", "created", "saved"), trace.seen());
    }

    @Test
    void defaultObserverRunsEveryMethodInTransaction() {
        Trace trace = new Trace(false);
        Article.QUERY.events().observe(trace);

        fx.real.transaction(tx -> {
            Article.QUERY.on(inTransaction(tx)).make(Map.of("title", "Watched")).save();
            assertEquals(Arrays.asList("saving", "creating", "created", "saved"), trace.seen());
            return null;
        });
    }

    @Test
    void observerRegisteredOnTheAfterCommitViewDefersWithoutOptingIn() {
        Trace trace = new Trace(false);
        Article.QUERY.events().afterCommit().observe(trace);

        fx.real.transaction(tx -> {
            Article.QUERY.on(inTransaction(tx)).make(Map.of("title", "Watched")).save();
            assertEquals(Arrays.asList("saving", "creating"), trace.seen());
            return null;
        });

        assertEquals(Arrays.asList("saving", "creating", "created", "saved"), trace.seen());
    }

    @Test
    void vetoFromAHaltingMethodStillStopsTheSaveAndDefersNothing() {
        Trace trace = new Trace(true);
        trace.vetoSaving = true;
        Article.QUERY.events().observe(trace);

        fx.real.transaction(tx -> {
            assertFalse(Article.QUERY.on(inTransaction(tx)).make(Map.of("title", "Vetoed")).save());
            return null;
        });

        assertEquals(Arrays.asList("saving"), trace.seen());
        assertEquals(0, fx.count("articles", "\"title\" = 'Vetoed'"));
    }

    @Test
    void retrievedKeepsRunningAtOnceInsideATransaction() {
        Trace trace = new Trace(true);
        Article.QUERY.events().observe(trace);

        fx.real.transaction(tx -> {
            assertTrue(Article.QUERY.on(inTransaction(tx)).query().find(1).isPresent());
            assertEquals(Arrays.asList("retrieved"), trace.seen());
            return null;
        });
    }

    @Test
    void rolledBackTransactionNeverCallsTheDeferredMethods() {
        Trace trace = new Trace(true);
        Article.QUERY.events().observe(trace);

        try {
            fx.real.transaction(tx -> {
                Article.QUERY.on(inTransaction(tx)).make(Map.of("title", "Dropped")).save();
                throw new IllegalStateException("rolled back");
            });
        } catch (IllegalStateException expected) {
            assertEquals("rolled back", expected.getMessage());
        }

        assertEquals(Arrays.asList("saving", "creating"), trace.seen());
    }

    /** Logs the name of each method it is called for; the opt-in and the saving veto are set by the test. */
    private static final class Trace implements ModelObserver<Article> {
        private final boolean isAfterCommit;
        private final List<String> seen = Collections.synchronizedList(new ArrayList<>());
        boolean vetoSaving;

        Trace(boolean isAfterCommit) {
            this.isAfterCommit = isAfterCommit;
        }

        List<String> seen() {
            synchronized (seen) {
                return new ArrayList<>(seen);
            }
        }

        @Override
        public boolean afterCommit() {
            return isAfterCommit;
        }

        @Override
        public boolean saving(Article model) {
            seen.add("saving");
            return !vetoSaving;
        }

        @Override
        public boolean creating(Article model) {
            seen.add("creating");
            return true;
        }

        @Override
        public void created(Article model) {
            seen.add("created");
        }

        @Override
        public void saved(Article model) {
            seen.add("saved");
        }

        @Override
        public void retrieved(Article model) {
            seen.add("retrieved");
        }
    }
}

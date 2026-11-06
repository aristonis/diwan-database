package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.ModelObserver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * The after-commit flows shared by the SQLite and the PostgreSQL test: the same listener
 * registrations and the same expectations on both databases. Every flow registers on
 * {@link Article#QUERY}, so the calling test forgets the listeners in {@code @AfterEach}.
 */
final class AfterCommitFlows {

    private AfterCommitFlows() {
    }

    /** Binds a model entry to the connection of a transaction callback, so its writes join the transaction. */
    private static QueryFactory inTransaction(EventFixtures fx, Connection tx) {
        return QueryFactory.of(tx, fx.db.grammar(), fx.db.processor());
    }

    /** The listener waits for the commit and then finds the committed row. */
    static void listenerRunsAfterCommitAndSeesTheRow(EventFixtures fx) {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Article.QUERY.events().afterCommit().saved(
                m -> seen.add("saved:" + m.getAttribute("title") + ":" + fx.count("articles", "title = 'Late'")));

        fx.real.transaction(tx -> {
            assertTrue(Article.QUERY.on(inTransaction(fx, tx)).make(Map.of("title", "Late")).save());
            assertTrue(seen.isEmpty());
            return null;
        });

        assertEquals(Arrays.asList("saved:Late:1"), seen);
    }

    /** A rolled back transaction never calls the listener, and its row is gone. */
    static void rollbackDropsTheListener(EventFixtures fx) {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Article.QUERY.events().afterCommit().saved(m -> seen.add("saved"));

        assertThrows(IllegalStateException.class, () -> fx.real.transaction(tx -> {
            Article.QUERY.on(inTransaction(fx, tx)).make(Map.of("title", "Never")).save();
            throw new IllegalStateException("rolled back");
        }));

        assertTrue(seen.isEmpty());
        assertEquals(0, fx.count("articles", "title = 'Never'"));
    }

    /** A save outside a transaction runs the listener at once. */
    static void saveOutsideATransactionRunsAtOnce(EventFixtures fx) {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Article.QUERY.events().afterCommit().created(m -> seen.add("created"));

        assertTrue(fx.articles().make(Map.of("title", "Solo")).save());

        assertEquals(Arrays.asList("created"), seen);
    }

    /** {@code saveOrFail} runs the listener after its own transaction committed. */
    static void saveOrFailRunsAfterItsOwnCommit(EventFixtures fx) {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Article.QUERY.events().saved(m -> seen.add("in-transaction"));
        Article.QUERY.events().afterCommit().saved(
                m -> seen.add("after-commit:" + fx.count("articles", "title = 'Whole'")));

        assertTrue(fx.articles().make(Map.of("title", "Whole")).saveOrFail());

        assertEquals(Arrays.asList("in-transaction", "after-commit:1"), seen);
    }

    /** An observer that opts in defers its non-halting methods only. */
    static void observerDefersOnlyNonHaltingMethods(EventFixtures fx) {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Article.QUERY.events().observe(new ModelObserver<Article>() {
            @Override
            public boolean afterCommit() {
                return true;
            }

            @Override
            public boolean saving(Article model) {
                seen.add("saving");
                return true;
            }

            @Override
            public void saved(Article model) {
                seen.add("saved");
            }
        });

        fx.real.transaction(tx -> {
            Article.QUERY.on(inTransaction(fx, tx)).make(Map.of("title", "Watched")).save();
            assertEquals(Arrays.asList("saving"), seen);
            return null;
        });

        assertEquals(Arrays.asList("saving", "saved"), seen);
    }
}

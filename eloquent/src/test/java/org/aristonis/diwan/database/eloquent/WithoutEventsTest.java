package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.eloquent.EventFixtures.Recorder;
import org.aristonis.diwan.database.eloquent.EventFixtures.TaggedPivot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** withoutEvents mutes the listeners of the calling thread only, and always restores the mute level. */
class WithoutEventsTest {

    private static final long TIMEOUT_SECONDS = 30L;

    private EventFixtures fx;

    @BeforeEach
    void open() throws SQLException {
        fx = EventFixtures.open();
        fx.seed();
    }

    @AfterEach
    void close() throws SQLException {
        EventFixtures.forgetAll();
        assertFalse(EventMute.isMuted(), "a test must leave the mute cleared");
        fx.close();
    }

    @Test
    void mutedWriteRunsTheSqlAndSilencesTheListeners() {
        Recorder recorder = new Recorder();
        Article.QUERY.events().observe(recorder);
        Article fresh = fx.articles().make(java.util.Map.of("title", "Silent"));

        boolean saved = Model.withoutEvents(() -> {
            return fresh.save();
        });

        assertTrue(saved);
        assertTrue(recorder.events().isEmpty());
        assertEquals(1, fx.count("articles", "\"title\" = 'Silent'"));
    }

    @Test
    void runnableFormMutesAndUnmutes() {
        AtomicInteger calls = new AtomicInteger();
        Article.QUERY.events().saved(m -> calls.incrementAndGet());
        Article article = fx.articles().query().find(1).get();

        Model.withoutEvents(() -> {
            assertTrue(EventMute.isMuted());
            article.save();
        });

        assertEquals(0, calls.get());
        assertFalse(EventMute.isMuted());
        article.save();
        assertEquals(1, calls.get());
    }

    @Test
    void supplierFormReturnsTheWorkResult() {
        String result = Model.withoutEvents(() -> {
            return "done";
        });

        assertEquals("done", result);
    }

    @Test
    void nestedInnerFailureRestoresTheOuterMuteThenClearsIt() {
        AtomicInteger calls = new AtomicInteger();
        Article.QUERY.events().saved(m -> calls.incrementAndGet());
        Article article = fx.articles().query().find(1).get();

        Model.withoutEvents(() -> {
            assertThrows(IllegalStateException.class, () -> Model.withoutEvents((Runnable) () -> {
                throw new IllegalStateException("inner failed");
            }));

            assertTrue(EventMute.isMuted(), "the outer level is still muted");
            article.save();
        });

        assertEquals(0, calls.get());
        assertFalse(EventMute.isMuted());
    }

    @Test
    void failureInTheOnlyLevelClearsTheMute() {
        assertThrows(IllegalStateException.class, () -> Model.withoutEvents((Runnable) () -> {
            throw new IllegalStateException("failed");
        }));

        assertFalse(EventMute.isMuted());
    }

    @Test
    void muteEndsOnlyWhenTheOutermostLevelEnds() {
        Model.withoutEvents(() -> {
            Model.withoutEvents(() -> {
                assertTrue(EventMute.isMuted());
            });

            assertTrue(EventMute.isMuted());
        });

        assertFalse(EventMute.isMuted());
    }

    @Test
    void muteDoesNotReachAnotherThread() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Article.QUERY.events().saved(m -> calls.incrementAndGet());
        Article here = Article.QUERY.newModel();
        Article there = Article.QUERY.newModel();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Model.withoutEvents(() -> {
                here.fire(ModelEvent.SAVED);
                try {
                    pool.submit(() -> there.fire(ModelEvent.SAVED)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, calls.get(), "only the other thread fired its listener");
    }

    @Test
    void unmutedThreadSeesNoMuteWhileThisThreadIsMuted() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            boolean[] seenByOther = new boolean[1];
            Model.withoutEvents(() -> {
                try {
                    seenByOther[0] = pool.submit(EventMute::isMuted).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertFalse(seenByOther[0]);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ownHookStillRunsAndVetoesWhileMuted() {
        Article article = fx.articles().query().find(1).get();
        article.setAttribute("title", "Changed");
        article.vetoSaving = true;
        List<String> expectedHooks = Collections.singletonList("saving");

        boolean saved = Model.withoutEvents(() -> {
            return article.save();
        });

        assertFalse(saved);
        assertEquals(expectedHooks, article.ownHooks);
    }

    @Test
    void pivotKeyGuardStillThrowsWhileMuted() {
        TaggedPivot pivot = fx.pivots().query().get().get(0);
        pivot.setAttribute("tag_id", 9);

        assertThrows(IllegalStateException.class, () -> Model.withoutEvents(() -> {
            pivot.save();
        }));

        assertFalse(EventMute.isMuted());
    }
}

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
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.eloquent.EventFixtures.Recorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Halting events: a false answer stops the operation and the later listeners; true lets them run. */
class ModelEventHaltTest {

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

    private Article loaded(int id) {
        return fx.articles().query().withTrashed().find(id).get();
    }

    @Test
    void haltingSavingListenerStopsTheSaveWithoutSql() {
        List<String> calls = new ArrayList<>();
        Article.QUERY.events()
                .saving(m -> {
                    calls.add("first");
                    return false;
                })
                .saving(m -> {
                    calls.add("second");
                    return true;
                })
                .saved(m -> calls.add("saved"));
        Article article = loaded(1);
        article.setAttribute("title", "Changed");
        fx.log.clear();

        boolean saved = article.save();

        assertFalse(saved);
        assertTrue(fx.log.writes().isEmpty());
        assertEquals(Collections.singletonList("first"), calls);
    }

    @Test
    void haltingCreatingListenerStopsTheInsert() {
        Recorder recorder = new Recorder().veto(ModelEvent.CREATING);
        Article.QUERY.events().observe(recorder);
        Article fresh = fx.articles().make(Map.of("title", "New"));
        fx.log.clear();

        boolean saved = fresh.save();

        assertFalse(saved);
        assertFalse(fresh.exists());
        assertTrue(fx.log.writes().isEmpty());
        assertEquals(Arrays.asList("saving:null", "creating:null"), recorder.events());
    }

    @Test
    void haltingUpdatingListenerStopsTheUpdate() {
        Article article = loaded(1);
        article.setAttribute("title", "Changed");
        Article.QUERY.events().updating(m -> false);
        fx.log.clear();

        boolean saved = article.save();

        assertFalse(saved);
        assertTrue(fx.log.writes().isEmpty());
        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"title\" = 'One'"));
    }

    @Test
    void haltingDeletingListenerStopsTheDelete() {
        Article article = loaded(1);
        Article.QUERY.events().deleting(m -> false);
        fx.log.clear();

        boolean deleted = article.delete();

        assertFalse(deleted);
        assertTrue(fx.log.writes().isEmpty());
        assertFalse(article.trashed());
    }

    @Test
    void haltingRestoringListenerKeepsTheModelTrashed() {
        Article trashed = loaded(3);
        Article.QUERY.events().restoring(m -> false);
        fx.log.clear();

        boolean restored = trashed.restore();

        assertFalse(restored);
        assertTrue(fx.log.writes().isEmpty());
        assertTrue(trashed.trashed());
    }

    @Test
    void haltingForceDeletingListenerKeepsTheRow() {
        Article article = loaded(1);
        Article.QUERY.events().forceDeleting(m -> false);
        fx.log.clear();

        boolean deleted = article.forceDelete();

        assertFalse(deleted);
        assertTrue(fx.log.writes().isEmpty());
        assertEquals(1, fx.count("articles", "\"id\" = 1"));
    }

    @Test
    void ownHookVetoKeepsEveryListenerSilent() {
        List<String> calls = new ArrayList<>();
        Article.QUERY.events().saving(m -> {
            calls.add("saving");
            return true;
        }).saved(m -> calls.add("saved"));
        Article article = loaded(1);
        article.setAttribute("title", "Changed");
        article.vetoSaving = true;
        fx.log.clear();

        boolean saved = article.save();

        assertFalse(saved);
        assertTrue(calls.isEmpty());
        assertEquals(Collections.singletonList("saving"), article.ownHooks);
        assertTrue(fx.log.writes().isEmpty());
    }

    @Test
    void ownDeletingVetoKeepsEveryListenerSilent() {
        List<String> calls = new ArrayList<>();
        Article.QUERY.events().deleting(m -> {
            calls.add("deleting");
            return true;
        });
        Article article = loaded(1);
        article.vetoDeleting = true;

        boolean deleted = article.delete();

        assertFalse(deleted);
        assertTrue(calls.isEmpty());
    }

    @Test
    void listenersReturningTrueLetLaterListenersRun() {
        List<String> calls = new ArrayList<>();
        Article.QUERY.events()
                .saving(m -> {
                    calls.add("first");
                    return true;
                })
                .saving(m -> {
                    calls.add("second");
                    return true;
                });
        Article article = loaded(1);
        article.setAttribute("title", "Changed");

        boolean saved = article.save();

        assertTrue(saved);
        assertEquals(Arrays.asList("first", "second"), calls);
        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"title\" = 'Changed'"));
    }

    @Test
    void haltingObserverSkipsTheObserversAfterIt() {
        Recorder first = new Recorder().veto(ModelEvent.SAVING);
        Recorder second = new Recorder();
        Article.QUERY.events().observe(first).observe(second);
        Article article = loaded(1);
        first.clear();
        second.clear();
        article.setAttribute("title", "Changed");

        boolean saved = article.save();

        assertFalse(saved);
        assertEquals(Collections.singletonList("saving:1"), first.events());
        assertTrue(second.events().isEmpty());
    }

    @Test
    void nonHaltingListenersCannotStopTheOperation() {
        Article.QUERY.events().saved(m -> {
            // A consumer has no way to answer false.
        }).created(m -> {
            // Nothing to veto here either.
        });
        Article fresh = fx.articles().make(Map.of("title", "New"));

        boolean saved = fresh.save();

        assertTrue(saved);
        assertTrue(fresh.exists());
    }
}

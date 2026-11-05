package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.eloquent.EventFixtures.Recorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Each quiet method does its write and runs no listener, even a listener that would veto. */
class QuietWritesTest {

    private EventFixtures fx;
    private Recorder recorder;

    @BeforeEach
    void open() throws SQLException {
        fx = EventFixtures.open();
        fx.seed();
        recorder = new Recorder().veto(ModelEvent.SAVING, ModelEvent.UPDATING, ModelEvent.DELETING,
                ModelEvent.RESTORING, ModelEvent.FORCE_DELETING, ModelEvent.CREATING);
    }

    @AfterEach
    void close() throws SQLException {
        EventFixtures.forgetAll();
        assertFalse(EventMute.isMuted(), "a quiet method must leave the mute cleared");
        fx.close();
    }

    private Article loaded(int id) {
        Article article = fx.articles().query().withTrashed().find(id).get();
        Article.QUERY.events().observe(recorder);
        recorder.clear();
        return article;
    }

    @Test
    void saveQuietlyInsertsAndStaysSilent() {
        Article.QUERY.events().observe(recorder);
        Article fresh = fx.articles().make(Map.of("title", "Quiet insert"));

        boolean saved = fresh.saveQuietly();

        assertTrue(saved);
        assertTrue(fresh.exists());
        assertTrue(recorder.events().isEmpty());
        assertEquals(1, fx.count("articles", "\"title\" = 'Quiet insert'"));
    }

    @Test
    void saveQuietlyUpdatesAndStaysSilent() {
        Article article = loaded(1);
        article.setAttribute("title", "Quiet update");

        boolean saved = article.saveQuietly();

        assertTrue(saved);
        assertTrue(recorder.events().isEmpty());
        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"title\" = 'Quiet update'"));
    }

    @Test
    void updateQuietlyWritesAndStaysSilent() {
        Article article = loaded(1);

        boolean updated = article.updateQuietly(Map.of("title", "Quiet map"));

        assertTrue(updated);
        assertTrue(recorder.events().isEmpty());
        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"title\" = 'Quiet map'"));
    }

    @Test
    void deleteQuietlySoftDeletesAndStaysSilent() {
        Article article = loaded(1);

        boolean deleted = article.deleteQuietly();

        assertTrue(deleted);
        assertTrue(article.trashed());
        assertTrue(recorder.events().isEmpty());
        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"deleted_at\" is not null"));
    }

    @Test
    void restoreQuietlyRestoresAndStaysSilent() {
        Article trashed = loaded(3);

        boolean restored = trashed.restoreQuietly();

        assertTrue(restored);
        assertFalse(trashed.trashed());
        assertTrue(recorder.events().isEmpty());
        assertEquals(1, fx.count("articles", "\"id\" = 3 and \"deleted_at\" is null"));
    }

    @Test
    void forceDeleteQuietlyDeletesTheRowAndStaysSilent() {
        Article article = loaded(1);

        boolean deleted = article.forceDeleteQuietly();

        assertTrue(deleted);
        assertTrue(recorder.events().isEmpty());
        assertEquals(0, fx.count("articles", "\"id\" = 1"));
    }

    @Test
    void touchQuietlyWritesTheTimestampAndStaysSilent() {
        Article article = loaded(1);

        boolean touched = article.touchQuietly();

        assertTrue(touched);
        assertTrue(recorder.events().isEmpty());
        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"updated_at\" is not null"));
    }

    @Test
    void touchQuietlyOfANamedColumnWritesItAndStaysSilent() {
        Article article = loaded(1);

        boolean touched = article.touchQuietly("created_at");

        assertTrue(touched);
        assertTrue(recorder.events().isEmpty());
        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"created_at\" is not null"));
    }

    @Test
    void replicateQuietlyReturnsACopyAndStaysSilent() {
        Article original = loaded(1);

        Article copy = original.replicateQuietly();

        assertNotNull(copy);
        assertNull(copy.getKey());
        assertEquals("One", copy.getAttribute("title"));
        assertTrue(recorder.events().isEmpty());
    }

    @Test
    void replicateQuietlyKeepsTheExceptList() {
        Article original = loaded(1);

        Article copy = original.replicateQuietly("title");

        assertNull(copy.getAttribute("title"));
    }

    @Test
    void quietMethodsLeaveLaterWritesLoud() {
        Article article = loaded(1);
        article.setAttribute("title", "Quiet");
        article.saveQuietly();
        article.setAttribute("title", "Loud");

        boolean saved = article.save();

        assertFalse(saved, "the vetoing listener is back in force");
        assertEquals("[saving:1]", recorder.events().toString());
    }

    @Test
    void quietMethodRestoresTheMuteWhenTheWriteFails() {
        Article article = loaded(1);
        article.setAttribute("title", "Quiet");
        fx.real.statement("drop table articles");

        try {
            article.saveQuietly();
        } catch (RuntimeException expected) {
            assertFalse(EventMute.isMuted());
            return;
        }
        throw new AssertionError("the write against a dropped table must fail");
    }
}

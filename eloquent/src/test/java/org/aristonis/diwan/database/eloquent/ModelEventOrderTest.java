package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

/** The order of save, update, increment and replicate events, and the operations that fire none. */
class ModelEventOrderTest {

    private EventFixtures fx;
    private Recorder recorder;

    @BeforeEach
    void open() throws SQLException {
        fx = EventFixtures.open();
        fx.seed();
        recorder = new Recorder();
    }

    @AfterEach
    void close() throws SQLException {
        EventFixtures.forgetAll();
        fx.close();
    }

    private Article loaded(int id) {
        Article article = fx.articles().query().find(id).get();
        recorder.clear();
        return article;
    }

    @Test
    void insertFiresSavingCreatingCreatedSaved() {
        Article.QUERY.events().observe(recorder);
        Article fresh = fx.articles().make(Map.of("title", "New"));

        assertTrue(fresh.save());

        String id = String.valueOf(fresh.getKey());
        assertEquals(Arrays.asList("saving:null", "creating:null", "created:" + id, "saved:" + id),
                recorder.events());
    }

    @Test
    void dirtyUpdateFiresSavingUpdatingUpdatedSaved() {
        Article article = loaded(1);
        Article.QUERY.events().observe(recorder);
        article.setAttribute("title", "Changed");

        assertTrue(article.save());

        assertEquals(Arrays.asList("saving:1", "updating:1", "updated:1", "saved:1"), recorder.events());
    }

    @Test
    void cleanSaveFiresSavingAndSavedButNotUpdating() {
        Article article = loaded(1);
        Article.QUERY.events().observe(recorder);
        fx.log.clear();

        assertTrue(article.save());

        assertEquals(Arrays.asList("saving:1", "saved:1"), recorder.events());
        assertTrue(fx.log.writes().isEmpty());
    }

    @Test
    void updateByMapFiresTheSaveEvents() {
        Article article = loaded(1);
        Article.QUERY.events().observe(recorder);

        assertTrue(article.update(Map.of("title", "Mapped")));

        assertEquals(Arrays.asList("saving:1", "updating:1", "updated:1", "saved:1"), recorder.events());
    }

    @Test
    void incrementFiresUpdatingThenUpdatedOnly() {
        Article article = loaded(1);
        Article.QUERY.events().observe(recorder);

        int rows = article.increment("views", 2);

        assertEquals(1, rows);
        assertEquals(Arrays.asList("updating:1", "updated:1"), recorder.events());
        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"views\" = 2"));
    }

    @Test
    void decrementFiresUpdatingThenUpdatedOnly() {
        Article article = loaded(1);
        Article.QUERY.events().observe(recorder);

        int rows = article.decrement("views", 1);

        assertEquals(1, rows);
        assertEquals(Arrays.asList("updating:1", "updated:1"), recorder.events());
        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"views\" = -1"));
    }

    @Test
    void incrementVetoedByAnUpdatingListenerReturnsZeroWithoutSql() {
        Article article = loaded(1);
        Article.QUERY.events().updating(m -> false);
        fx.log.clear();

        int rows = article.increment("views");

        assertEquals(0, rows);
        assertTrue(fx.log.writes().isEmpty());
        assertEquals(0, ((Number) article.getAttribute("views")).intValue());
    }

    @Test
    void decrementVetoedByAnUpdatingListenerReturnsZeroWithoutSql() {
        Article article = loaded(1);
        Article.QUERY.events().updating(m -> false);
        fx.log.clear();

        int rows = article.decrement("views");

        assertEquals(0, rows);
        assertTrue(fx.log.writes().isEmpty());
    }

    @Test
    void replicateFiresReplicatingOnTheCopyAfterItsAttributesAreSet() {
        Article original = loaded(1);
        List<Article> copies = new ArrayList<>();
        List<Object> titlesSeen = new ArrayList<>();
        Article.QUERY.events().replicating(m -> {
            copies.add(m);
            titlesSeen.add(m.getAttribute("title"));
        });

        Article copy = original.replicate();

        assertEquals(1, copies.size());
        assertNotSame(original, copies.get(0));
        assertTrue(copy == copies.get(0), "the listener got the copy that replicate returned");
        assertEquals(Collections.singletonList("One"), titlesSeen);
        assertNull(copy.getKey());
    }

    @Test
    void replicateObserverLogsTheCopyWithoutAKey() {
        Article original = loaded(1);
        Article.QUERY.events().observe(recorder);

        original.replicate();

        assertEquals(Collections.singletonList("replicating:null"), recorder.events());
    }

    @Test
    void builderMassUpdateAndDeleteFireNoEvents() {
        Article.QUERY.events().observe(recorder);

        int updated = fx.articles().query().where("title", "One").update(Map.of("views", 5));
        int deleted = fx.articles().query().where("title", "Two").delete();

        assertEquals(1, updated);
        assertEquals(1, deleted);
        assertTrue(recorder.events().isEmpty());
    }

    @Test
    void builderIncrementFiresNoEvents() {
        Article.QUERY.events().observe(recorder);

        fx.articles().query().where("title", "One").increment("views");

        assertTrue(recorder.events().isEmpty());
    }

    @Test
    void unsavedModelDeleteFiresNothing() {
        Article.QUERY.events().observe(recorder);
        Article fresh = fx.articles().make(Map.of("title", "New"));

        assertFalse(fresh.delete());

        assertTrue(recorder.events().isEmpty());
    }

    @Test
    void sameModelSavedListenerThatSavesAgainRecursesUntilTheStackOverflows() {
        Article.QUERY.events().saved(m -> m.save());
        Article article = loaded(1);

        assertThrows(StackOverflowError.class, article::save);
    }
}

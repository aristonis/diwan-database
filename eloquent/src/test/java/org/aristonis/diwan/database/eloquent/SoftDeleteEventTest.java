package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.eloquent.EventFixtures.Recorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Events of soft delete, restore and force delete: order, vetoes, and what a listener can see. */
class SoftDeleteEventTest {

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
        Article article = fx.articles().query().withTrashed().find(id).get();
        recorder.clear();
        return article;
    }

    @Test
    void softDeleteFiresDeletingSoftDeletedThenDeleted() {
        Article article = loaded(1);
        Article.QUERY.events().observe(recorder);

        assertTrue(article.delete());

        assertEquals(Arrays.asList("deleting:1", "softDeleted:1", "deleted:1"), recorder.events());
    }

    @Test
    void softDeletedListenerSeesTheTrashedModel() {
        List<Boolean> seen = new ArrayList<>();
        Article.QUERY.events().softDeleted(m -> seen.add(m.trashed()));
        Article article = loaded(1);

        article.delete();

        assertEquals(Collections.singletonList(Boolean.TRUE), seen);
    }

    @Test
    void softDeleteOfAVanishedRowStillFiresSoftDeleted() {
        Article article = loaded(1);
        fx.real.statement("delete from \"articles\" where \"id\" = 1");
        Article.QUERY.events().observe(recorder);

        boolean deleted = article.delete();

        assertTrue(deleted);
        assertEquals(Arrays.asList("deleting:1", "softDeleted:1", "deleted:1"), recorder.events());
    }

    @Test
    void forceDeleteVetoedByAListenerKeepsTheRowAndClearsTheFlag() {
        Article article = loaded(1);
        Article.QUERY.events().forceDeleting(m -> false);
        fx.log.clear();

        boolean deleted = article.forceDelete();

        assertFalse(deleted);
        assertFalse(article.isForceDeleting());
        assertTrue(fx.log.writes().isEmpty());
        assertEquals(1, fx.count("articles", "\"id\" = 1"));
    }

    @Test
    void forceDeleteFiresForceDeletingDeletingDeletedForceDeletedWithoutSoftDeleted() {
        Article article = loaded(1);
        Article.QUERY.events().observe(recorder);

        assertTrue(article.forceDelete());

        assertEquals(Arrays.asList("forceDeleting:1", "deleting:1", "deleted:1", "forceDeleted:1"),
                recorder.events());
        assertEquals(0, fx.count("articles", "\"id\" = 1"));
    }

    @Test
    void deletingAndDeletedListenersSeeTheForceFlagDuringForceDelete() {
        List<String> seen = new ArrayList<>();
        Article.QUERY.events()
                .deleting(m -> {
                    seen.add("deleting=" + m.isForceDeleting());
                    return true;
                })
                .deleted(m -> seen.add("deleted=" + m.isForceDeleting()))
                .forceDeleted(m -> seen.add("forceDeleted=" + m.isForceDeleting()));
        Article article = loaded(1);

        article.forceDelete();

        assertEquals(Arrays.asList("deleting=true", "deleted=true", "forceDeleted=false"), seen);
    }

    @Test
    void deletingListenerVetoDuringForceDeleteKeepsTheRowAndClearsTheFlag() {
        Article article = loaded(1);
        Article.QUERY.events().deleting(m -> false);
        fx.log.clear();

        boolean deleted = article.forceDelete();

        assertFalse(deleted);
        assertFalse(article.isForceDeleting());
        assertEquals(1, fx.count("articles", "\"id\" = 1"));
    }

    @Test
    void restoreOfATrashedModelFiresItsEventsInOrder() {
        Article trashed = loaded(3);
        Article.QUERY.events().observe(recorder);

        assertTrue(trashed.restore());

        assertEquals(Arrays.asList("restoring:3", "saving:3", "updating:3", "updated:3", "saved:3", "restored:3"),
                recorder.events());
        assertFalse(trashed.trashed());
    }

    @Test
    void restoreOfALiveModelFiresRestoringAndRestoredWithoutAnUpdate() {
        Article live = loaded(1);
        Article.QUERY.events().observe(recorder);
        fx.log.clear();

        boolean restored = live.restore();

        assertTrue(restored);
        List<String> events = recorder.events();
        assertTrue(events.contains("restoring:1"));
        assertTrue(events.contains("restored:1"));
        assertTrue(events.indexOf("restoring:1") < events.indexOf("restored:1"));
        assertFalse(events.contains("updating:1"));
        assertTrue(fx.log.writes().isEmpty());
    }

    @Test
    void restoreVetoedByAListenerKeepsTheModelTrashed() {
        Article trashed = loaded(3);
        Article.QUERY.events().restoring(m -> false);
        fx.log.clear();

        boolean restored = trashed.restore();

        assertFalse(restored);
        assertTrue(trashed.trashed());
        assertTrue(fx.log.writes().isEmpty());
    }

    @Test
    void restoredListenerSeesALiveModel() {
        List<Boolean> seen = new ArrayList<>();
        Article.QUERY.events().restored(m -> seen.add(m.trashed()));
        Article trashed = loaded(3);

        trashed.restore();

        assertEquals(Collections.singletonList(Boolean.FALSE), seen);
    }
}

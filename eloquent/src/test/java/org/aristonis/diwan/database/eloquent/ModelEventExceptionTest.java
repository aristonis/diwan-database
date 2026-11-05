package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A listener that throws: the exception reaches the caller, later listeners stay quiet, and saveOrFail rolls back. */
class ModelEventExceptionTest {

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

    @Test
    void createdListenerFailureRollsBackSaveOrFailAndRestoresTheModel() {
        Article.QUERY.events().created(m -> {
            throw new IllegalStateException("created failed");
        });
        Article fresh = fx.articles().make(Map.of("title", "Boom"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, fresh::saveOrFail);

        assertEquals("created failed", thrown.getMessage());
        assertEquals(0, fx.count("articles", "\"title\" = 'Boom'"));
        assertFalse(fresh.exists());
        assertFalse(fresh.wasRecentlyCreated());
        assertNull(fresh.getKey());
    }

    @Test
    void savedListenerFailureOnUpdateRestoresTheDirtyState() {
        Article.QUERY.events().saved(m -> {
            throw new IllegalStateException("saved failed");
        });
        Article article = fx.articles().query().find(1).get();
        article.setAttribute("title", "Changed");

        assertThrows(IllegalStateException.class, article::saveOrFail);

        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"title\" = 'One'"));
        assertTrue(article.isDirty("title"));
        assertEquals("Changed", article.getAttribute("title"));
    }

    @Test
    void throwingListenerStopsTheListenersAfterIt() {
        List<String> calls = new ArrayList<>();
        Article.QUERY.events()
                .saving(m -> {
                    throw new IllegalArgumentException("saving failed");
                })
                .saving(m -> {
                    calls.add("second");
                    return true;
                });
        Article article = fx.articles().query().find(1).get();
        article.setAttribute("title", "Changed");
        fx.log.clear();

        assertThrows(IllegalArgumentException.class, article::save);

        assertTrue(calls.isEmpty());
        assertTrue(fx.log.writes().isEmpty());
    }

    @Test
    void throwingConsumerStopsTheConsumersAfterIt() {
        List<String> calls = new ArrayList<>();
        Article.QUERY.events()
                .created(m -> {
                    throw new IllegalStateException("created failed");
                })
                .created(m -> calls.add("second"));
        Article fresh = fx.articles().make(Map.of("title", "Boom"));

        assertThrows(IllegalStateException.class, fresh::save);

        assertEquals(Collections.emptyList(), calls);
    }

    @Test
    void errorFromAListenerPropagatesUnchanged() {
        Article.QUERY.events().saved(m -> {
            throw new AssertionError("listener error");
        });
        Article article = fx.articles().query().find(1).get();

        AssertionError thrown = assertThrows(AssertionError.class, article::save);

        assertEquals("listener error", thrown.getMessage());
    }

    @Test
    void failingListenerDoesNotBreakTheNextOperation() {
        Article.QUERY.events().deleting(m -> {
            throw new IllegalStateException("deleting failed");
        });
        Article article = fx.articles().query().find(1).get();
        assertThrows(IllegalStateException.class, article::delete);
        Article.QUERY.events().forget();

        boolean deleted = article.delete();

        assertTrue(deleted);
        assertTrue(article.trashed());
    }
}

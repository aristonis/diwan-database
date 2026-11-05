package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.eloquent.EventFixtures.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A listener that writes a second row inside saveOrFail: it must use the connection the model sees
 * inside the save, so both rows commit or roll back together, and a root-bound write is refused.
 */
class ListenerTransactionTest {

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

    private void auditOnSaved(String note) {
        Article.QUERY.events().saved(m -> AuditLog.QUERY.on(m.getQueryFactory())
                .create(Map.of("article_id", 1, "note", note)));
    }

    @Test
    void auditRowWrittenByASavedListenerCommitsWithTheArticle() {
        auditOnSaved("created");
        Article fresh = fx.articles().make(Map.of("title", "Audited"));

        assertTrue(fresh.saveOrFail());

        assertEquals(1, fx.count("articles", "\"title\" = 'Audited'"));
        assertEquals(1, fx.count("audit_logs", "\"note\" = 'created'"));
    }

    @Test
    void laterThrowRollsBackBothTheArticleAndTheAuditRow() {
        auditOnSaved("created");
        Article.QUERY.events().saved(m -> {
            throw new IllegalStateException("late failure");
        });
        Article fresh = fx.articles().make(Map.of("title", "Rolled"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, fresh::saveOrFail);

        assertEquals("late failure", thrown.getMessage());
        assertEquals(0, fx.count("articles", "\"title\" = 'Rolled'"));
        assertEquals(0, fx.count("audit_logs", "1 = 1"));
        assertFalse(fresh.exists());
    }

    @Test
    void failingAuditWriteRollsBackTheArticleToo() {
        Article.QUERY.events().saved(m -> {
            throw new IllegalArgumentException("audit failed");
        });
        Article article = fx.articles().query().find(1).get();
        article.setAttribute("title", "Changed");

        assertThrows(IllegalArgumentException.class, article::saveOrFail);

        assertEquals(1, fx.count("articles", "\"id\" = 1 and \"title\" = 'One'"));
    }

    @Test
    void auditWriteBoundToTheRootConnectionIsRefusedInStrictMode() {
        Article.QUERY.events().saved(m -> AuditLog.QUERY.on(fx.db)
                .create(Map.of("article_id", 1, "note", "root bound")));
        Article fresh = fx.articles().make(Map.of("title", "Strict"));

        assertThrows(IllegalStateException.class, fresh::saveOrFail);

        assertEquals(0, fx.count("articles", "\"title\" = 'Strict'"));
        assertEquals(0, fx.count("audit_logs", "1 = 1"));
    }

    @Test
    void plainSaveRunsNoTransactionSoTheListenerRowStaysEvenWhenALaterListenerThrows() {
        auditOnSaved("kept");
        Article.QUERY.events().saved(m -> {
            throw new IllegalStateException("late failure");
        });
        Article fresh = fx.articles().make(Map.of("title", "Plain"));

        assertThrows(IllegalStateException.class, fresh::save);

        assertEquals(1, fx.count("articles", "\"title\" = 'Plain'"));
        assertEquals(1, fx.count("audit_logs", "\"note\" = 'kept'"));
    }

    @Test
    void mutedSaveOrFailWritesNoAuditRow() {
        auditOnSaved("silent");
        Article fresh = fx.articles().make(Map.of("title", "Muted"));

        boolean saved = Model.withoutEvents(() -> {
            return fresh.saveOrFail();
        });

        assertTrue(saved);
        assertEquals(1, fx.count("articles", "\"title\" = 'Muted'"));
        assertEquals(0, fx.count("audit_logs", "1 = 1"));
    }
}

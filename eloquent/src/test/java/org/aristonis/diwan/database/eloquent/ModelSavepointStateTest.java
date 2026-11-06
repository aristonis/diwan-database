package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a model reads after a savepoint rolled back matches the database, not the undone write. */
class ModelSavepointStateTest {

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

    private static int views(Article article) {
        return ((Number) article.getAttribute("views")).intValue();
    }

    @Test
    void aRefreshAfterARolledBackSavepointShowsTheValueFromBeforeIt() {
        fx.real.transaction(tx -> {
            Article outer = Article.QUERY.on(inTransaction(tx)).find(1).orElseThrow();
            outer.update(Map.of("views", 5));
            assertThrows(IllegalStateException.class, () -> tx.transaction(inner -> {
                Article.QUERY.on(inTransaction(inner)).find(1).orElseThrow().update(Map.of("views", 9));
                throw new IllegalStateException("inner failed");
            }));

            outer.refresh();

            assertEquals(5, views(outer));
            return null;
        });

        assertEquals(5, ((Number) fx.real.scalar("select views from articles where id = 1").orElseThrow()).intValue());
    }

    @Test
    void aFreshModelLoadedAfterARolledBackSavepointSeesTheOuterWorkOnly() {
        fx.real.transaction(tx -> {
            Article.QUERY.on(inTransaction(tx)).find(1).orElseThrow().update(Map.of("views", 3));
            assertThrows(IllegalStateException.class, () -> tx.transaction(inner -> {
                Article.QUERY.on(inTransaction(inner)).find(1).orElseThrow().update(Map.of("views", 7));
                throw new IllegalStateException("inner failed");
            }));

            Article reloaded = Article.QUERY.on(inTransaction(tx)).find(1).orElseThrow();

            assertEquals(3, views(reloaded));
            return null;
        });
    }
}

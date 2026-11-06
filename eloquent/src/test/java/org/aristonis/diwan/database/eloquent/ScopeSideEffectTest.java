package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.EloquentAggregates;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.ScopeFixtures;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A global scope that changes eager loads, casts, aggregate selects or the row hook is refused by
 * every terminal, and the error names the scope key.
 */
class ScopeSideEffectTest {

    private static final String LOADER = "eagerLoader";
    private static final String CASTS = "castScope";
    private static final String EXISTS = "existsScope";
    private static final String COUNTER = "countScope";

    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures = ScopeFixtures.open();
        fixtures.seed();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures.close();
    }

    @Test
    void scopeCallingWithIsRefusedNamingTheKey() {
        Post.QUERY.addGlobalScope(LOADER, (EloquentBuilder<?> builder) -> {
            builder.with("user");
        });
        EloquentBuilder<Post> query = fixtures.posts().query();

        IllegalStateException refused = assertThrows(IllegalStateException.class, query::get);

        assertTrue(refused.getMessage().contains("'" + LOADER + "'"), refused.getMessage());
    }

    @Test
    void scopeCallingWithCastsIsRefusedNamingTheKey() {
        Post.QUERY.addGlobalScope(CASTS, (EloquentBuilder<?> builder) -> {
            builder.withCasts(Collections.singletonMap("views", Casts.integer()));
        });
        EloquentBuilder<Post> query = fixtures.posts().query();

        IllegalStateException refused = assertThrows(IllegalStateException.class, query::get);

        assertTrue(refused.getMessage().contains("'" + CASTS + "'"), refused.getMessage());
    }

    @Test
    void scopeCallingWithExistsIsRefusedNamingTheKey() {
        Post.QUERY.addGlobalScope(EXISTS, (EloquentBuilder<?> builder) -> {
            builder.withExists("user");
        });
        EloquentBuilder<Post> query = fixtures.posts().query();

        IllegalStateException refused = assertThrows(IllegalStateException.class, query::get);

        assertTrue(refused.getMessage().contains("'" + EXISTS + "'"), refused.getMessage());
    }

    /**
     * Expected to fail until production code refuses {@code withCount} the way it refuses {@code with}.
     * Reading {@code EloquentAggregates} and {@code RelationAggregates}, {@code withCount} adds a
     * select and changes no eager load, cast or row hook, so the current terminal does not refuse it.
     */
    @Test
    void refusedScopeFailsEveryTerminalNotOnlyGet() {
        Post.QUERY.addGlobalScope(LOADER, (EloquentBuilder<?> builder) -> {
            builder.with("user");
        });
        EloquentBuilder<Post> query = fixtures.posts().query();

        assertThrows(IllegalStateException.class, query::count);
        assertThrows(IllegalStateException.class, query::toSql);
        assertThrows(IllegalStateException.class, query::getBindings);
    }
}

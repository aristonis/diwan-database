package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.ScopeFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How often a registered scope is applied: once per terminal, and once per eager chunk however many
 * parent keys the chunk holds.
 */
class ScopeCostTest {

    private static final String COUNTED = "counted";

    private final AtomicInteger applies = new AtomicInteger();
    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures = ScopeFixtures.open();
        fixtures.seed();
        User.QUERY.addGlobalScope(COUNTED, (EloquentBuilder<?> builder) -> {
            applies.incrementAndGet();
        });
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures.close();
    }

    @Test
    void eachTerminalAppliesTheScopeOnce() {
        EloquentBuilder<User> users = fixtures.users().query();

        users.get();
        assertEquals(1, applies.get());

        users.count();
        assertEquals(2, applies.get());
    }

    @Test
    void oneEagerChunkAppliesTheRelatedScopeTheSameNumberOfTimesForAnyParentCount() {
        EloquentBuilder<Post> oneParent = fixtures.posts().query().where("id", 1).with("user");
        EloquentBuilder<Post> threeParents = fixtures.posts().query()
                .withoutGlobalScope(ScopeFixtures.TENANT).with("user");

        applies.set(0);
        oneParent.get();
        int forOneKey = applies.getAndSet(0);
        threeParents.get();
        int forThreeKeys = applies.get();

        assertTrue(forOneKey > 0, "the eager load must run the related scope");
        assertEquals(forOneKey, forThreeKeys);
    }
}

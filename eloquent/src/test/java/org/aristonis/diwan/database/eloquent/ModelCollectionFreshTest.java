package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ModelCollection.fresh(): reloads every model unscoped, so trashed and out-of-scope rows stay. */
class ModelCollectionFreshTest {

    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        fixtures = ScopeFixtures.open();
        fixtures.seed();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures.close();
    }

    private static List<Long> keysOf(Iterable<Post> posts) {
        List<Long> keys = new ArrayList<>();
        for (Post post : posts) {
            keys.add(Long.parseLong(String.valueOf(post.getKey())));
        }
        return keys;
    }

    @Test
    void freshKeepsTrashedModels() {
        ModelCollection<Post> loaded = fixtures.posts().query().withTrashed().whereKey(Arrays.asList(1, 4)).get();

        ModelCollection<Post> fresh = loaded.fresh();

        assertEquals(Arrays.asList(1L, 4L), keysOf(fresh));
        assertTrue(fresh.get(1).trashed());
    }

    @Test
    void freshKeepsModelsOutsideTheTenantScope() {
        ModelCollection<Post> loaded = fixtures.posts().query().withoutGlobalScopes()
                .whereKey(Arrays.asList(1, 5)).get();

        ModelCollection<Post> fresh = loaded.fresh();

        assertEquals(Arrays.asList(1L, 5L), keysOf(fresh));
        assertEquals("Post 5", fresh.get(1).getAttribute("title"));
    }

    @Test
    void freshReloadsEachRowFromTheDatabase() {
        ModelCollection<Post> loaded = fixtures.posts().query().whereKey(1).get();
        fixtures.real.statement("update posts set title = 'Changed' where id = 1");

        ModelCollection<Post> fresh = loaded.fresh();

        assertEquals("Changed", fresh.get(0).getAttribute("title"));
        assertEquals("Post 1", loaded.get(0).getAttribute("title"));
    }

    @Test
    void freshDropsRowsDeletedSinceTheLoad() {
        ModelCollection<Post> loaded = fixtures.posts().query().withoutGlobalScopes()
                .whereKey(Arrays.asList(1, 5)).get();
        fixtures.real.statement("delete from posts where id = 5");

        ModelCollection<Post> fresh = loaded.fresh();

        assertEquals(Collections.singletonList(1L), keysOf(fresh));
        assertFalse(fresh.contains(loaded.get(1)));
    }

    @Test
    void freshOfAnEmptyCollectionIsTheSameCollection() {
        ModelCollection<Post> none = fixtures.posts().query().whereKey(999).get();

        assertSame(none, none.fresh());
    }
}

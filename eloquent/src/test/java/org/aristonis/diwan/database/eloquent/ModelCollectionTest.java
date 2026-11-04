package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.junit.jupiter.api.Test;

class ModelCollectionTest {

    private final FakeDb fake = new FakeDb();
    private final ModelQuery<User> users = User.QUERY.on(fake.db);

    private ModelCollection<User> two() {
        return users.hydrate(Arrays.asList(row("id", 1L, "name", "Ann"), row("id", 2, "name", "Bob")));
    }

    @Test
    void findMatchesKeysLooselyAsLaravel() {
        ModelCollection<User> models = two();

        assertEquals("Ann", models.find(1).get().getAttribute("name"));
        assertEquals("Bob", models.find("2").get().getAttribute("name"));
        assertEquals("Bob", models.find(models.get(1)).get().getAttribute("name"));
        assertFalse(models.find(3).isPresent());
    }

    @Test
    void containsTakesAModelOrAKey() {
        ModelCollection<User> models = two();
        User other = users.newModel();
        other.setRawAttributes(row("id", 2L), true);

        assertTrue(models.contains(other));
        assertTrue(models.contains(1L));
        assertFalse(models.contains(7));
        assertFalse(models.contains(null));
    }

    @Test
    void theCollectionIsReadOnly() {
        ModelCollection<User> models = two();

        assertThrows(UnsupportedOperationException.class, () -> models.add(users.newModel()));
        assertThrows(UnsupportedOperationException.class, () -> models.remove(0));
        assertEquals(Arrays.asList(1L, 2), models.modelKeys());
    }

    @Test
    void freshReloadsInTheSameOrderAndDropsVanishedRows() {
        fake.returns("select * from \"users\" where \"users\".\"id\" in (?, ?)", row("id", 2L, "name", "B2"));
        ModelCollection<User> models = two();

        ModelCollection<User> fresh = models.fresh();

        assertEquals(1, fresh.size());
        assertEquals("B2", fresh.get(0).getAttribute("name"));
        assertTrue(users.hydrate(Collections.emptyList()).fresh().isEmpty());
        assertSame(fresh, fresh);
    }

    @Test
    void loadReturnsThisWithoutAQueryWhenThereIsNothingToLoad() {
        ModelCollection<User> none = users.hydrate(Collections.emptyList());
        ModelCollection<User> models = two();

        assertSame(none, none.load("posts"));
        assertSame(none, none.load("posts", q -> q.where("id", 1)));
        assertSame(none, none.loadMissing("posts"));
        assertSame(models, models.load(Collections.emptyMap()));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void loadAggregatesReturnThisWithoutAQueryOnAnEmptyCollection() {
        ModelCollection<User> none = users.hydrate(Collections.emptyList());

        assertSame(none, none.loadCount("posts"));
        assertSame(none, none.loadCount(Collections.emptyMap()));
        assertSame(none, none.loadMax("posts", "id"));
        assertSame(none, none.loadMin(Collections.emptyMap(), "id"));
        assertSame(none, none.loadSum("posts", "id"));
        assertSame(none, none.loadAvg("posts", "id"));
        assertSame(none, none.loadExists("posts"));
        assertSame(none, none.loadExists(Collections.emptyMap()));
        assertSame(none, none.loadAggregate("posts", "id", "max"));
        assertSame(none, none.loadAggregate(Collections.emptyMap(), "id", "max"));
        assertSame(none, none.loadAggregate("posts", "id"));
        assertTrue(fake.calls.isEmpty());
    }
}

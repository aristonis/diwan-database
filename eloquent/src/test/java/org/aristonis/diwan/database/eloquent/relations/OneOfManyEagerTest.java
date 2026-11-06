package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.scope.SoftDeletingScope;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Db;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Order;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.StringKeyUser;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Eager loading of one-of-many relations on SQLite: one order query for all parents, trashed rows
 * lifted by the eager closure, qualified column lists, and the chunk size when keys are bound twice.
 */
class OneOfManyEagerTest {

    /** The bound keys per statement on SQLite. */
    private static final int SQLITE_BOUND_LIMIT = 900;

    private static final int PARENT_COUNT = 1000;

    private Db db;

    @BeforeEach
    void open() throws SQLException {
        db = Db.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private static String day(int day) {
        return OneOfManyModels.day(day);
    }

    private static Long keyOf(org.aristonis.diwan.database.eloquent.Model model) {
        return OneOfManyModels.key(model);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private void seedThreeUsers() {
        db.user(1L, null, "Ann");
        db.user(2L, null, "Ben");
        db.user(3L, null, "Cleo");
        db.order(1, 1, day(1), 10, "paid");
        db.order(2, 1, day(2), 20, "paid");
        db.order(3, 2, day(1), 30, "paid");
    }

    @Test
    void eagerPicksPerParent() {
        // Arrange
        seedThreeUsers();

        // Act
        ModelCollection<User> users = User.QUERY.on(db.db).query().with("latestOrder").orderBy("id").get();

        // Assert
        assertEquals(1, db.readsOn("orders").size(), db.queries().toString());
        assertEquals(2L, keyOf(users.get(0).<Order>related("latestOrder")));
        assertEquals(3L, keyOf(users.get(1).<Order>related("latestOrder")));
        assertTrue(users.get(2).relationLoaded("latestOrder"));
        assertNull(users.get(2).<Order>related("latestOrder"));
    }

    @Test
    void eagerTieTakesTheHigherKeyPerParent() {
        // Arrange
        db.user(1L, null, "Ann");
        db.user(2L, null, "Ben");
        db.order(1, 1, day(4), 10, "paid");
        db.order(2, 1, day(4), 10, "paid");
        db.order(3, 2, day(4), 10, "paid");
        db.order(4, 2, day(4), 10, "paid");

        // Act
        ModelCollection<User> users = User.QUERY.on(db.db).query().with("latestByCreated").orderBy("id").get();

        // Assert
        assertEquals(2L, keyOf(users.get(0).<Order>related("latestByCreated")));
        assertEquals(4L, keyOf(users.get(1).<Order>related("latestByCreated")));
    }

    @Test
    void loadOnAnExistingCollectionPicksPerParent() {
        // Arrange
        seedThreeUsers();
        ModelCollection<User> users = User.QUERY.on(db.db).query().orderBy("id").get();
        db.clearLog();

        // Act
        users.load("latestOrder");

        // Assert
        assertEquals(1, db.readsOn("orders").size());
        assertEquals(2L, keyOf(users.get(0).<Order>related("latestOrder")));
        assertEquals(3L, keyOf(users.get(1).<Order>related("latestOrder")));
        assertNull(users.get(2).<Order>related("latestOrder"));
    }

    @Test
    void columnListQualified() {
        // Arrange
        seedThreeUsers();

        // Act
        ModelCollection<User> users = User.QUERY.on(db.db).query().with("latestOrder:id,price,user_id")
                .orderBy("id").get();

        // Assert
        String sql = db.readsOn("orders").get(0).sql();
        assertTrue(sql.startsWith("select \"orders\".\"id\", \"orders\".\"price\", \"orders\".\"user_id\""
                + " from \"orders\" inner join ("), sql);
        Order picked = users.get(0).related("latestOrder");
        Set<String> attributes = new HashSet<>(picked.getAttributes().keySet());
        assertEquals(new HashSet<>(Arrays.asList("id", "price", "user_id")), attributes);
        assertEquals(20, ((Number) picked.getAttribute("price")).intValue());
    }

    @Test
    void eagerSkipsATrashedNewestRow() {
        // Arrange
        seedThreeUsers();
        db.trashedOrder(4, 1, day(9), 10, "paid");

        // Act
        ModelCollection<User> users = User.QUERY.on(db.db).query().with("latestOrder").orderBy("id").get();

        // Assert
        assertEquals(2L, keyOf(users.get(0).<Order>related("latestOrder")));
    }

    @Test
    void withTrashedInTheEagerClosureLetsTheTrashedRowWin() {
        // Arrange
        seedThreeUsers();
        db.trashedOrder(4, 1, day(9), 10, "paid");

        // Act
        ModelCollection<User> users = User.QUERY.on(db.db).query()
                .with("latestOrder", q -> q.withTrashed()).orderBy("id").get();

        // Assert
        assertEquals(4L, keyOf(users.get(0).<Order>related("latestOrder")));
        assertEquals(3L, keyOf(users.get(1).<Order>related("latestOrder")));
    }

    @Test
    void withoutGlobalScopeInTheEagerClosureLetsTheTrashedRowWin() {
        // Arrange
        seedThreeUsers();
        db.trashedOrder(4, 1, day(9), 10, "paid");

        // Act
        ModelCollection<User> users = User.QUERY.on(db.db).query()
                .with("latestOrder", q -> q.withoutGlobalScope(SoftDeletingScope.NAME)).orderBy("id").get();

        // Assert
        assertEquals(4L, keyOf(users.get(0).<Order>related("latestOrder")));
    }

    @Test
    void withTrashedInLoadLetsTheTrashedRowWin() {
        // Arrange
        seedThreeUsers();
        db.trashedOrder(4, 1, day(9), 10, "paid");
        ModelCollection<User> users = User.QUERY.on(db.db).query().orderBy("id").get();

        // Act
        users.load("latestOrder", q -> q.withTrashed());

        // Assert
        assertEquals(4L, keyOf(users.get(0).<Order>related("latestOrder")));
    }

    @Test
    void integerKeysAreInlinedInBothFilters() {
        // Arrange
        seedThreeUsers();

        // Act
        User.QUERY.on(db.db).query().with("latestOrder").get();

        // Assert
        Db.Query read = db.readsOn("orders").get(0);
        assertTrue(read.bindings().isEmpty(), read.bindings().toString());
        assertEquals(2, occurrences(read.sql(), "\"user_id\" in (1, 2, 3)"), read.sql());
    }

    @Test
    void eagerChunkBoundaryWithStringKeysStaysUnderTheBindingLimit() {
        // Arrange: a string key is bound twice per parent, so a chunk holds half the usual keys
        db.user(1L, null, "Ann");
        db.user(2L, null, "Ben");
        db.user(3L, null, "Cleo");
        db.order(1, 1, day(1), 10, "paid");
        db.order(2, 1, day(2), 10, "paid");
        db.order(3, 2, day(1), 10, "paid");
        db.order(4, 3, day(1), 10, "paid");
        ModelCollection<StringKeyUser> parents = StringKeyUser.QUERY.on(db.db).hydrate(parentRows());
        db.clearLog();

        // Act
        parents.load("latestOrder");

        // Assert
        List<Db.Query> reads = db.readsOn("orders");
        assertTrue(reads.size() >= 3, "the keys must be split in at least three statements: " + reads.size());
        for (Db.Query read : reads) {
            assertTrue(read.bindings().size() <= SQLITE_BOUND_LIMIT, read.bindings().size() + " bindings");
            assertEquals(2, occurrences(read.sql(), "\"user_id\" in ("), read.sql());
        }
        assertEquals(2L, keyOf(parents.get(0).<Order>related("latestOrder")));
        assertEquals(3L, keyOf(parents.get(PARENT_COUNT / 2).<Order>related("latestOrder")));
        assertEquals(4L, keyOf(parents.get(PARENT_COUNT - 1).<Order>related("latestOrder")));
        assertNull(parents.get(1).<Order>related("latestOrder"));
    }

    @Test
    void eagerChunkBoundaryCoversEveryParentOnce() {
        // Arrange
        ModelCollection<StringKeyUser> parents = StringKeyUser.QUERY.on(db.db).hydrate(parentRows());
        db.clearLog();

        // Act
        parents.load("latestOrder");

        // Assert: no order exists, so every parent is loaded empty and every key was sent once
        int sent = 0;
        for (Db.Query read : db.readsOn("orders")) {
            sent += read.bindings().size() / 2;
        }
        assertEquals(PARENT_COUNT, sent);
        for (StringKeyUser parent : parents) {
            assertTrue(parent.relationLoaded("latestOrder"));
            assertFalse(parent.<Order>related("latestOrder") != null);
        }
    }

    private static List<Map<String, ?>> parentRows() {
        List<Map<String, ?>> rows = new ArrayList<>();
        for (int i = 0; i < PARENT_COUNT; i++) {
            String id = i == 0 ? "1" : i == PARENT_COUNT / 2 ? "2" : i == PARENT_COUNT - 1 ? "3" : "x" + i;
            rows.add(Map.of("id", id, "name", "p" + i));
        }
        return rows;
    }
}

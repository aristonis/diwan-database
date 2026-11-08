package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.aristonis.diwan.database.testing.Fixtures.Tag;
import org.aristonis.diwan.database.testing.Fixtures.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The table and model assertions, standalone on a SQLite file, with no extension. */
class DatabaseAssertionsTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String SECRET = "hunter2";

    /** A user model that also hides its key, to check the key is never printed. */
    static final class VaultUser extends Model {
        static final ModelQuery<VaultUser> QUERY = ModelQuery.of(VaultUser.class, VaultUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> hidden() {
            return Arrays.asList("id", "password");
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private QueryFactory db;
    private DatabaseAssertions assertions;

    @BeforeEach
    void migrate() {
        Connection connection = Fixtures.sqlite("sqlite-assert-" + DATABASES.incrementAndGet());
        Migrator.builder(SQLiteSchema.of(connection)).migrations(Fixtures.withTags()).build().run();
        db = SQLiteQuery.of(connection);
        assertions = DatabaseAssertions.of(db);
    }

    private void addUsers(String... names) {
        for (String name : names) {
            Map<String, Object> row = new HashMap<>();
            row.put("name", name);
            row.put("password", SECRET);
            db.table("users").insert(row);
        }
    }

    private static String failure(Executable executable) {
        return assertThrows(AssertionError.class, executable).getMessage();
    }

    private User savedUser() {
        User user = User.QUERY.on(db).make(Map.of("name", "ann", "password", SECRET));
        assertTrue(user.save());
        return user;
    }

    // ---- assertHas and assertMissing ----

    @Test
    void assertHasPassesForAMatchingRowAndMatchesNull() {
        db.table("users").insert(Collections.singletonMap("name", "ann"));

        assertDoesNotThrow(() -> assertions.assertHas("users", Map.of("name", "ann")));
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("name", "ann");
        withNull.put("password", null);
        assertDoesNotThrow(() -> assertions.assertHas("users", withNull));
    }

    @Test
    void assertHasFailureNamesTheColumnsAndListsThreeSimilarRowsOfAll() {
        addUsers("a", "b", "c", "d", "e");

        String message = failure(() -> assertions.assertHas("users", Map.of("name", "zed")));

        assertTrue(message.contains("No row in [users] matches {name=zed}"), message);
        assertTrue(message.contains("Similar rows (first 3 of 5): {id=1, name=a}, {id=2, name=b}, {id=3, name=c}"),
                message);
        assertFalse(message.contains(SECRET), message);
        assertFalse(message.contains("password"), message);
    }

    @Test
    void assertHasFailureOnAnEmptyTableSaysSo() {
        String message = failure(() -> assertions.assertHas("users", Map.of("name", "ann")));

        assertTrue(message.contains("The table is empty."), message);
    }

    @Test
    void assertHasRejectsBadArguments() {
        assertThrows(NullPointerException.class, () -> assertions.assertHas(null, Map.of("a", 1)));
        assertThrows(IllegalArgumentException.class, () -> assertions.assertHas(" ", Map.of("a", 1)));
        assertThrows(IllegalArgumentException.class, () -> assertions.assertHas("users", Collections.emptyMap()));
        assertThrows(NullPointerException.class, () -> assertions.assertHas("users", null));
        assertThrows(NullPointerException.class, () -> DatabaseAssertions.of(null));
    }

    @Test
    void assertMissingPassesWhenNoRowMatchesAndFailsShowingTheMatch() {
        addUsers("ann");

        assertDoesNotThrow(() -> assertions.assertMissing("users", Map.of("name", "bob")));
        String message = failure(() -> assertions.assertMissing("users", Map.of("name", "ann")));

        assertTrue(message.contains("Found 1 row(s) in [users] that match {name=ann}"), message);
        assertTrue(message.contains("{id=1, name=ann}"), message);
        assertFalse(message.contains(SECRET), message);
    }

    // ---- assertCount and assertEmpty ----

    @Test
    void assertCountAndAssertEmptyPassAndFail() {
        assertDoesNotThrow(() -> assertions.assertEmpty("users"));
        addUsers("a", "b");

        assertDoesNotThrow(() -> assertions.assertCount("users", 2));
        assertTrue(failure(() -> assertions.assertCount("users", 5))
                .contains("Expected [users] to have 5 row(s), found 2."));
        assertTrue(failure(() -> assertions.assertEmpty("users")).contains("to have 0 row(s), found 2"));
    }

    // ---- models ----

    @Test
    void assertModelExistsPassesForASavedModelAndFailsForAMissingKey() {
        User user = savedUser();
        User ghost = User.QUERY.on(db).make(Map.of("id", 99, "name", "ghost", "password", SECRET));

        assertDoesNotThrow(() -> assertions.assertModelExists(user));
        String message = failure(() -> assertions.assertModelExists(ghost));

        assertTrue(message.contains("No row in [users] has id = 99"), message);
        assertTrue(message.contains("Similar rows (first 1 of 1): {id=1}"), message);
        assertFalse(message.contains(SECRET), message);
    }

    @Test
    void assertModelExistsFailsForAModelWithoutAKeyValue() {
        User unsaved = User.QUERY.on(db).make(Map.of("name", "new", "password", SECRET));

        String message = failure(() -> assertions.assertModelExists(unsaved));

        assertTrue(message.contains("has no value for its key [id]"), message);
        assertFalse(message.contains(SECRET), message);
    }

    @Test
    void assertModelMissingFailsForAnExistingRowWithoutPrintingHiddenValues() {
        User user = savedUser();
        User unsaved = User.QUERY.on(db).make(Map.of("name", "new"));

        String message = failure(() -> assertions.assertModelMissing(user));

        assertTrue(message.contains("Found a row in [users] with id = " + user.getKey()), message);
        assertFalse(message.contains(SECRET), message);
        assertDoesNotThrow(() -> assertions.assertModelMissing(unsaved));
    }

    @Test
    void assertModelMissingPassesAfterAForceDelete() {
        User user = savedUser();

        assertTrue(user.forceDelete());

        assertDoesNotThrow(() -> assertions.assertModelMissing(user));
        assertThrows(AssertionError.class, () -> assertions.assertModelExists(user));
    }

    @Test
    void softDeleteAssertionsFollowTheDeletedAtColumn() {
        User live = savedUser();
        User gone = savedUser();
        assertTrue(gone.delete());

        assertDoesNotThrow(() -> assertions.assertNotSoftDeleted(live));
        assertDoesNotThrow(() -> assertions.assertSoftDeleted(gone));
        assertTrue(failure(() -> assertions.assertSoftDeleted(live)).contains("is not soft deleted"));
        assertTrue(failure(() -> assertions.assertNotSoftDeleted(gone)).contains("is soft deleted"));
    }

    @Test
    void softDeleteAssertionOnAMissingRowFailsAsMissing() {
        User ghost = User.QUERY.on(db).make(Map.of("id", 42, "name", "ghost"));

        assertTrue(failure(() -> assertions.assertSoftDeleted(ghost)).contains("No row in [users] has id = 42"));
    }

    @Test
    void softDeleteAssertionOnAModelWithoutSoftDeletesSaysSo() {
        Tag tag = Tag.QUERY.on(db).make(Map.of("label", "java"));
        assertTrue(tag.save());

        assertTrue(failure(() -> assertions.assertSoftDeleted(tag)).contains("does not soft-delete"));
        assertTrue(failure(() -> assertions.assertNotSoftDeleted(tag)).contains("does not soft-delete"));
        assertDoesNotThrow(() -> assertions.assertModelExists(tag));
    }

    @Test
    void aHiddenKeyIsNeverPrinted() {
        VaultUser ghost = VaultUser.QUERY.on(db).make(Map.of("id", 777, "name", "ghost", "password", SECRET));
        addUsers("ann");

        String message = failure(() -> assertions.assertModelExists(ghost));

        assertTrue(message.contains("[hidden]"), message);
        assertFalse(message.contains("777"), message);
        assertFalse(message.contains(SECRET), message);
        assertFalse(message.contains("{id="), message);
    }

    @Test
    void modelAssertionsRejectANullModel() {
        assertThrows(NullPointerException.class, () -> assertions.assertModelExists(null));
        assertThrows(NullPointerException.class, () -> assertions.assertModelMissing(null));
        assertThrows(NullPointerException.class, () -> assertions.assertSoftDeleted(null));
        assertThrows(NullPointerException.class, () -> assertions.assertNotSoftDeleted(null));
    }
}

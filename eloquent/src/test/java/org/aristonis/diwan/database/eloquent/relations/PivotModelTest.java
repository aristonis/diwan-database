package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pivot rows: how they are built, what they report, and the guards that refuse writes while a
 * pivot has no key value. Each test opens its own in-memory database.
 */
class PivotModelTest {

    private static final String ROLE_USER = "role_user";
    private static final String KEYED = "keyed_pivots";
    private static final String CREATED = "2024-01-01 10:00:00";

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void fromAttributesState() {
        // Arrange
        Tenant parent = new Tenant();
        parent.setConnection(db.db);
        Map<String, ?> attributes = Map.of("user_id", 1, "role_id", 1, "status", "active",
                "created_at", CREATED, "updated_at", CREATED);

        // Act
        Pivot pivot = Pivot.fromAttributes(parent, attributes, ROLE_USER, true);

        // Assert
        assertTrue(pivot.exists());
        assertFalse(pivot.isDirty());
        assertEquals("active", pivot.getAttribute("status"));
        assertEquals("tenant", pivot.getConnectionName());
        assertEquals(ROLE_USER, pivot.getTable());
        assertTrue(pivot.usesTimestamps());
        assertSame(parent, pivot.getPivotParent());
    }

    @Test
    void timestampsKeyedOnCreatedAt() {
        // Arrange
        BtmModels.User user = user();
        Map<String, ?> attributes = Map.of("user_id", 1, "role_id", 1, "updated_at", CREATED);

        // Act
        Pivot pivot = Pivot.fromAttributes(user, attributes, ROLE_USER, true);

        // Assert
        assertFalse(pivot.usesTimestamps());
    }

    @Test
    void notExisting() {
        // Arrange
        Map<String, ?> attributes = Map.of("user_id", 1, "role_id", 2, "status", "pending");

        // Act
        Pivot pivot = Pivot.fromAttributes(user(), attributes, ROLE_USER, false);

        // Assert
        assertFalse(pivot.exists());
        assertFalse(pivot.isDirty());
        assertEquals("pending", pivot.getAttribute("status"));
    }

    @Test
    void fromRawAttributes() {
        // Arrange
        Map<String, ?> attributes = Map.of("user_id", 1, "role_id", 1, "status", "active");

        // Act
        Pivot pivot = Pivot.fromRawAttributes(user(), attributes, ROLE_USER, true);

        // Assert
        assertTrue(pivot.exists());
        assertFalse(pivot.isDirty());
        assertEquals("active", pivot.getAttribute("status"));
    }

    @Test
    void rawNotExistingDirty() {
        // Arrange
        Map<String, ?> attributes = Map.of("user_id", 1, "role_id", 1, "status", "active");

        // Act
        Pivot pivot = Pivot.fromRawAttributes(user(), attributes, ROLE_USER, false);

        // Assert
        assertFalse(pivot.exists());
        assertTrue(pivot.isDirty());
        assertEquals("active", pivot.getAttribute("status"));
    }

    @Test
    void unguarded() {
        // Arrange
        Pivot pivot = keyless(user());

        // Act
        pivot.fill(Map.of("anything", "accepted"));

        // Assert
        assertTrue(pivot.getGuarded().isEmpty());
        assertEquals("accepted", pivot.getAttribute("anything"));
    }

    @Test
    void notIncrementing() {
        // Arrange
        Pivot pivot = new Pivot();

        // Act
        boolean incrementing = pivot.getIncrementing();

        // Assert
        assertFalse(incrementing);
    }

    @Test
    void defaultTable() {
        // Arrange
        RoleUser pivot = new RoleUser();

        // Act
        String table = pivot.getTable();

        // Assert
        assertEquals("role_user", table);
    }

    @Test
    void noSingular() {
        // Arrange
        RoleUsers pivot = new RoleUsers();

        // Act
        String table = pivot.getTable();

        // Assert
        assertEquals("role_users", table);
    }

    @Test
    void tableOverride() {
        // Arrange
        Map<String, ?> attributes = Map.of("user_id", 1, "role_id", 1);

        // Act
        Pivot byOverride = Pivot.fromRawAttributes(new Tagged(), user(), attributes, null, true);
        Pivot byGiven = Pivot.fromRawAttributes(new Tagged(), user(), attributes, ROLE_USER, true);

        // Assert
        assertEquals("tagged_pivots", byOverride.getTable());
        assertEquals(ROLE_USER, byGiven.getTable());
    }

    @Test
    void timestampNamesFromParent() {
        // Arrange
        Stamped parent = new Stamped();
        Map<String, ?> attributes = Map.of("user_id", 1, "role_id", 1, "made", CREATED);

        // Act
        Pivot pivot = Pivot.fromAttributes(parent, attributes, ROLE_USER, true);

        // Assert
        assertEquals("made", pivot.getCreatedAtColumn());
        assertEquals("changed", pivot.getUpdatedAtColumn());
        assertTrue(pivot.usesTimestamps());
    }

    @Test
    void noParentDefaults() {
        // Arrange
        Pivot pivot = new Pivot();

        // Act
        String createdAt = pivot.getCreatedAtColumn();
        String updatedAt = pivot.getUpdatedAtColumn();

        // Assert
        assertEquals("created_at", createdAt);
        assertEquals("updated_at", updatedAt);
        assertNull(pivot.getPivotParent());
    }

    @Test
    void keyNames() {
        // Arrange
        Pivot pivot = Pivot.fromAttributes(user(), Map.of("user_id", 1, "role_id", 1), ROLE_USER, true);
        String foreignKeyBefore = pivot.getForeignKey();

        // Act
        pivot.setPivotKeys("user_id", "role_id");

        // Assert
        assertEquals("user_id", pivot.getForeignPivotKeyName());
        assertEquals("role_id", pivot.getRelatedPivotKeyName());
        assertEquals(foreignKeyBefore, pivot.getForeignKey());
    }

    @Test
    void setKeys() {
        // Arrange
        Pivot pivot = new Pivot();
        BtmModels.Role role = role(1);

        // Act
        Pivot keyed = pivot.setPivotKeys("user_id", "role_id");
        Pivot related = keyed.setRelatedModel(role);

        // Assert
        assertSame(pivot, keyed);
        assertSame(pivot, related);
        assertEquals("user_id", pivot.getForeignPivotKeyName());
        assertEquals("role_id", pivot.getRelatedPivotKeyName());
        assertSame(role, pivot.getRelatedModel());
    }

    @Test
    void ofHelper() {
        // Arrange
        BtmModels.Role withPivot = role(1);
        BtmModels.Role withoutPivot = role(2);
        Pivot pivot = Pivot.fromAttributes(user(), Map.of("user_id", 1, "role_id", 1), ROLE_USER, true);
        withPivot.setRelation("pivot", pivot);

        // Act
        Pivot found = Pivot.of(withPivot);
        Pivot missing = Pivot.of(withoutPivot);

        // Assert
        assertSame(pivot, found);
        assertNull(missing);
    }

    @Test
    void ofNullWhenNonPivotLoadedUnderPivotName() {
        // Arrange
        BtmModels.Role role = role(1);
        role.setRelation("pivot", role(2));

        // Act
        Pivot found = Pivot.of(role);

        // Assert
        assertNull(found);
    }

    @Test
    void ofHelperAccessor() {
        // Arrange
        BtmModels.Role role = role(1);
        Pivot pivot = Pivot.fromAttributes(user(), Map.of("user_id", 1, "role_id", 1), ROLE_USER, true);
        role.setRelation("membership", pivot);

        // Act
        Pivot found = Pivot.of(role, "membership");
        Pivot other = Pivot.of(role, "pivot");

        // Assert
        assertSame(pivot, found);
        assertNull(other);
    }

    @Test
    void ofNull() {
        // Act
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> Pivot.of(null));

        // Assert
        assertTrue(thrown.getMessage().contains("related"));
    }

    @Test
    void unregistered() {
        // Arrange
        NoEntryPivot pivot = Pivot.fromRawAttributes(new NoEntryPivot(), user(),
                Map.of("id", 1, "name", "admin"), "roles", true);
        pivot.setConnection(db.db);

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pivot.fresh());

        // Assert
        assertTrue(thrown.getMessage().contains(NoEntryPivot.class.getName()));
    }

    @Test
    void updateWithoutKeyThrows() {
        // Arrange
        Pivot pivot = keyless(user());
        pivot.setAttribute("status", "inactive");

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pivot.save());

        // Assert
        assertTrue(thrown.getMessage().contains(ROLE_USER));
        assertFalse(thrown.getMessage().contains("inactive"));
    }

    @Test
    void cleanKeylessSaveRunsNoUpdate() {
        // Arrange
        Pivot pivot = keyless(user());

        // Act
        boolean saved = pivot.save();

        // Assert
        assertTrue(saved);
        assertFalse(pivot.isDirty());
    }

    @Test
    void deleteWithoutKeyThrows() {
        // Arrange
        Pivot pivot = keyless(user());

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pivot.delete());

        // Assert
        assertTrue(thrown.getMessage().contains(ROLE_USER));
    }

    @Test
    void updateWithPivotKeysWritesCompositeRow() {
        // Arrange
        Pivot pivot = keyless(user()).setPivotKeys("user_id", "role_id");
        pivot.setAttribute("status", "inactive");

        // Act
        boolean saved = pivot.save();

        // Assert
        assertTrue(saved);
        assertEquals("inactive", db.real.scalar(
                "select status from role_user where user_id = 1 and role_id = 1").orElseThrow());
        assertEquals("pending", db.real.scalar(
                "select status from role_user where user_id = 1 and role_id = 2").orElseThrow());
    }

    @Test
    void deleteWithPivotKeysRemovesCompositeRow() {
        // Arrange
        Pivot pivot = keyless(user()).setPivotKeys("user_id", "role_id");

        // Act
        boolean deleted = pivot.delete();

        // Assert
        assertTrue(deleted);
        assertFalse(pivot.exists());
        assertEquals(0, ((Number) db.real.scalar(
                "select count(*) from role_user where user_id = 1 and role_id = 1").orElseThrow()).intValue());
        assertEquals(1, ((Number) db.real.scalar(
                "select count(*) from role_user where user_id = 1 and role_id = 2").orElseThrow()).intValue());
    }

    @Test
    void incrementWithoutKeyThrows() {
        // Arrange
        Pivot pivot = keyless(user());

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> pivot.increment("weight", 1, Collections.<String, Object>emptyMap()));

        // Assert
        assertTrue(thrown.getMessage().contains(ROLE_USER));
        assertEquals(5, pivot.getAttribute("weight"));
    }

    @Test
    void incrementShortFormWithoutKeyThrows() {
        // Arrange
        Pivot pivot = keyless(user());

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pivot.increment("weight"));

        // Assert
        assertTrue(thrown.getMessage().contains(ROLE_USER));
        assertEquals(5, pivot.getAttribute("weight"));
    }

    @Test
    void decrementWithoutKeyThrows() {
        // Arrange
        Pivot pivot = keyless(user());

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> pivot.decrement("weight", 1, Collections.<String, Object>emptyMap()));

        // Assert
        assertTrue(thrown.getMessage().contains(ROLE_USER));
        assertEquals(5, pivot.getAttribute("weight"));
    }

    @Test
    void refreshWithoutKeyThrows() {
        // Arrange
        Pivot pivot = keyless(user());

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pivot.refresh());

        // Assert
        assertTrue(thrown.getMessage().contains(ROLE_USER));
    }

    @Test
    void freshWithoutKeyThrows() {
        // Arrange
        Pivot pivot = keyless(user());

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pivot.fresh());

        // Assert
        assertTrue(thrown.getMessage().contains(ROLE_USER));
    }

    @Test
    void pushWithDirtyKeylessPivotThrowsAfterEarlierSaves() {
        // Arrange
        BtmModels.User user = user();
        user.setAttribute("name", "Owner");
        BtmModels.Role role = role(1);
        role.setAttribute("name", "root");
        Pivot pivot = keyless(user);
        pivot.setAttribute("status", "inactive");
        role.setRelation("pivot", pivot);
        user.setRelation("roles", role);

        // Act
        assertThrows(IllegalStateException.class, () -> user.push());

        // Assert
        assertEquals("Owner", user().getAttribute("name"));
        assertEquals("root", role(1).getAttribute("name"));
    }

    @Test
    void keyedUpdateWritesRow() {
        // Arrange
        createKeyedTable();
        db.seed(KEYED, keyedRow(1, "active", 5));
        Pivot pivot = keyedPivot(keyedRow(1, "active", 5));
        pivot.setAttribute("status", "inactive");

        // Act
        boolean saved = pivot.save();

        // Assert
        assertTrue(saved);
        assertEquals("inactive", storedRow(1).orElseThrow().getAttribute("status"));
    }

    @Test
    void keyedDeleteRemovesRow() {
        // Arrange
        createKeyedTable();
        db.seed(KEYED, keyedRow(1, "active", 5));
        Pivot pivot = keyedPivot(keyedRow(1, "active", 5));

        // Act
        boolean deleted = pivot.delete();

        // Assert
        assertTrue(deleted);
        assertFalse(pivot.exists());
        assertFalse(storedRow(1).isPresent());
    }

    @Test
    void keyedIncrementUpdatesRow() {
        // Arrange
        createKeyedTable();
        db.seed(KEYED, keyedRow(1, "active", 5));
        Pivot pivot = keyedPivot(keyedRow(1, "active", 5));

        // Act
        int affected = pivot.increment("weight", 2);

        // Assert
        assertEquals(1, affected);
        assertEquals(7, ((Number) storedRow(1).orElseThrow().getAttribute("weight")).intValue());
    }

    @Test
    void keyedRefreshReloadsRow() {
        // Arrange
        createKeyedTable();
        db.seed(KEYED, keyedRow(1, "stored", 5));
        Pivot pivot = keyedPivot(keyedRow(1, "stale", 5));

        // Act
        pivot.refresh();

        // Assert
        assertEquals("stored", pivot.getAttribute("status"));
    }

    @Test
    void keyedFreshReturnsStoredCopy() {
        // Arrange
        createKeyedTable();
        db.seed(KEYED, keyedRow(1, "stored", 5));
        Pivot pivot = keyedPivot(keyedRow(1, "stale", 5));

        // Act
        Optional<Pivot> copy = pivot.fresh();

        // Assert
        assertTrue(copy.isPresent());
        assertNotSame(pivot, copy.get());
        assertEquals("stored", copy.get().getAttribute("status"));
    }

    private void createKeyedTable() {
        SQLiteSchema.of(db.real).create(KEYED, t -> {
            t.id();
            t.integer("user_id");
            t.integer("role_id");
            t.string("status");
            t.integer("weight");
        });
    }

    private static Map<String, Object> keyedRow(int id, String status, int weight) {
        return Map.of("id", id, "user_id", 1, "role_id", 1, "status", status, "weight", weight);
    }

    /** A pivot read through user 1 that has a key value, so the guards let writes through. */
    private Pivot keyedPivot(Map<String, ?> attributes) {
        return Pivot.fromAttributes(user(), attributes, KEYED, true);
    }

    private Optional<Pivot> storedRow(int id) {
        return keyedPivot(Map.of("id", id)).fresh();
    }

    private BtmModels.User user() {
        return BtmModels.User.QUERY.on(db.db).findOrFail(1);
    }

    private BtmModels.Role role(int id) {
        return BtmModels.Role.QUERY.on(db.db).findOrFail(id);
    }

    /** A stored pivot row with no key value, weight 5, read through the given owner. */
    private static Pivot keyless(Model owner) {
        return Pivot.fromAttributes(owner, Map.of("user_id", 1, "role_id", 1, "status", "active", "weight", 5),
                ROLE_USER, true);
    }

    /** A parent whose connection is named, so a pivot must take that name. */
    static final class Tenant extends Model {
        @Override
        protected String connectionName() {
            return "tenant";
        }
    }

    /** A parent whose timestamp columns are renamed. */
    static final class Stamped extends Model {
        @Override
        protected String createdAtColumn() {
            return "made";
        }

        @Override
        protected String updatedAtColumn() {
            return "changed";
        }
    }

    /** A pivot whose table comes from its class name, without a plural. */
    static final class RoleUser extends Pivot {
    }

    /** A pivot whose class name ends in s, kept as the table name. */
    static final class RoleUsers extends Pivot {
    }

    /** A pivot with its own table name. */
    static final class Tagged extends Pivot {
        @Override
        protected String table() {
            return "tagged_pivots";
        }
    }

    /** A pivot with no registry entry, read by hand. */
    static final class NoEntryPivot extends Pivot {
        @Override
        protected String table() {
            return "roles";
        }
    }
}

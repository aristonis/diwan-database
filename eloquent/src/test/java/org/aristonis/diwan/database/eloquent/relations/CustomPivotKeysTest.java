package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pivots that write by their two pivot key columns: a join table with no primary key, and a keyed
 * pivot whose key is missing or changed. Each test opens its own in-memory database and records
 * the write statements it runs.
 */
class CustomPivotKeysTest {

    private static final String ROLE_USER = "role_user";
    private static final String KEYED = "keyed_pivots";
    private static final String CREATED = "2024-01-01 10:00:00";
    private static final List<String> WRITES = Arrays.asList("update", "delete", "affectingStatement", "statement");
    private static final String COMPOSITE_WHERE = "where \"user_id\" = ? and \"role_id\" = ?";

    private BtmDb db;
    private List<String> writeSql;
    private List<List<Object>> writeBindings;
    private QueryFactory factory;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
        writeSql = new ArrayList<>();
        writeBindings = new ArrayList<>();
        factory = SQLiteQuery.of(recording(db.real));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void saveUsesBothPivotKeysWhenNoPrimaryKey() {
        // Arrange
        Pivot pivot = membership(Map.of("user_id", 1, "role_id", 2, "status", "pending"));
        pivot.setAttribute("status", "done");

        // Act
        boolean saved = pivot.save();

        // Assert
        assertTrue(saved);
        assertEquals(1, writeSql.size());
        assertEquals("update \"role_user\" set \"status\" = ? " + COMPOSITE_WHERE, writeSql.get(0));
        assertEquals(Arrays.<Object>asList("done", 1, 2), writeBindings.get(0));
        assertEquals("done", db.real.scalar("select status from role_user where user_id = 1 and role_id = 2")
                .orElseThrow());
        assertEquals("active", db.real.scalar("select status from role_user where user_id = 1 and role_id = 1")
                .orElseThrow());
    }

    @Test
    void saveUsesOriginalKeyValues() {
        // Arrange
        Pivot pivot = membership(Map.of("user_id", 2, "role_id", 1, "status", "active"));
        pivot.setAttribute("role_id", 2);

        // Act
        boolean saved = pivot.save();

        // Assert
        assertTrue(saved);
        assertEquals("update \"role_user\" set \"role_id\" = ? " + COMPOSITE_WHERE, writeSql.get(0));
        assertEquals(Arrays.<Object>asList(2, 2, 1), writeBindings.get(0));
        assertEquals(1, count("user_id = 2 and role_id = 2"));
        assertEquals(0, count("user_id = 2 and role_id = 1"));
    }

    @Test
    void deleteUsesBothPivotKeys() {
        // Arrange
        Pivot pivot = membership(Map.of("user_id", 2, "role_id", 1, "status", "active"));

        // Act
        boolean deleted = pivot.delete();

        // Assert
        assertTrue(deleted);
        assertFalse(pivot.exists());
        assertEquals(1, writeSql.size());
        assertEquals("delete from \"role_user\" " + COMPOSITE_WHERE, writeSql.get(0));
        assertEquals(Arrays.<Object>asList(2, 1), writeBindings.get(0));
        assertEquals(0, count("user_id = 2"));
        assertEquals(2, count("user_id = 1"));
    }

    @Test
    void deletingListenerCanVetoDelete() {
        // Arrange
        VetoPivot pivot = Pivot.fromRawAttributes(new VetoPivot(), user(),
                Map.of("user_id", 2, "role_id", 1, "status", "active"), ROLE_USER, true);
        pivot.setConnection(factory);
        pivot.setPivotKeys("user_id", "role_id");

        // Act
        boolean deleted = pivot.delete();

        // Assert
        assertFalse(deleted);
        assertTrue(writeSql.isEmpty());
        assertEquals(1, count("user_id = 2 and role_id = 1"));
    }

    @Test
    void incrementStillRefusedWithoutPrimaryKey() {
        // Arrange
        Pivot pivot = membership(Map.of("user_id", 1, "role_id", 1, "weight", 5));

        // Act
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> pivot.increment("weight", 1, Collections.<String, Object>emptyMap()));

        // Assert
        assertTrue(thrown.getMessage().contains(ROLE_USER));
        assertTrue(writeSql.isEmpty());
        assertEquals(5, pivot.getAttribute("weight"));
    }

    @Test
    void timestampsFollowCreatedAtPresence() {
        // Arrange
        Pivot stamped = membership(Map.of("user_id", 1, "role_id", 1, "status", "active",
                "created_at", CREATED, "updated_at", CREATED));
        Pivot plain = membership(Map.of("user_id", 1, "role_id", 2, "status", "pending"));
        stamped.setAttribute("status", "done");
        plain.setAttribute("status", "done");

        // Act
        stamped.save();
        plain.save();

        // Assert
        assertEquals(2, writeSql.size());
        assertTrue(writeSql.get(0).contains("\"updated_at\" = ?"));
        assertFalse(writeSql.get(1).contains("updated_at"));
    }

    @Test
    void keyedPivotChangedKeyUsesOriginalKey() {
        // Arrange
        createKeyedTable();
        db.seed(KEYED, Map.of("id", 1, "user_id", 1, "role_id", 1, "status", "active"));
        Pivot pivot = Pivot.fromAttributes(user(),
                Map.of("id", 1, "user_id", 1, "role_id", 1, "status", "active"), KEYED, true);
        pivot.setPivotKeys("user_id", "role_id");
        pivot.setAttribute("id", 9);

        // Act
        boolean saved = pivot.save();

        // Assert
        assertTrue(saved);
        assertEquals("update \"keyed_pivots\" set \"id\" = ? where \"id\" = ?", writeSql.get(0));
        assertEquals(Arrays.<Object>asList(9, 1), writeBindings.get(0));
    }

    @Test
    void keyedPivotWithNullIdFallsBackToPivotKeys() {
        // Arrange
        createKeyedTable();
        db.seed(KEYED, Map.of("id", 1, "user_id", 1, "role_id", 1, "status", "active"));
        Pivot pivot = Pivot.fromAttributes(user(),
                Map.of("user_id", 1, "role_id", 1, "status", "active"), KEYED, true);
        pivot.setPivotKeys("user_id", "role_id");
        pivot.setAttribute("status", "done");

        // Act
        boolean saved = pivot.save();

        // Assert
        assertTrue(saved);
        assertEquals("update \"keyed_pivots\" set \"status\" = ? " + COMPOSITE_WHERE, writeSql.get(0));
        assertEquals(Arrays.<Object>asList("done", 1, 1), writeBindings.get(0));
        assertEquals("done", db.real.scalar("select status from keyed_pivots where id = 1").orElseThrow());
    }

    private Pivot membership(Map<String, ?> attributes) {
        Pivot pivot = Pivot.fromAttributes(user(), attributes, ROLE_USER, true);
        pivot.setPivotKeys("user_id", "role_id");
        return pivot;
    }

    private BtmModels.User user() {
        return BtmModels.User.QUERY.on(factory).findOrFail(1);
    }

    private int count(String condition) {
        return ((Number) db.real.scalar("select count(*) from role_user where " + condition)
                .orElseThrow()).intValue();
    }

    private void createKeyedTable() {
        SQLiteSchema.of(db.real).create(KEYED, t -> {
            t.id();
            t.integer("user_id");
            t.integer("role_id");
            t.string("status");
        });
    }

    // Wraps a connection so every write statement it runs is recorded with its bindings.
    private Connection recording(Connection real) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (WRITES.contains(method.getName()) && args != null && args[0] instanceof String) {
                        writeSql.add((String) args[0]);
                        writeBindings.add(bindingsOf(args.length > 1 ? args[1] : null));
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static List<Object> bindingsOf(Object argument) {
        if (argument instanceof List) {
            return new ArrayList<>((List<Object>) argument);
        }
        return argument == null ? Collections.emptyList() : Arrays.asList((Object[]) argument);
    }

    /** A pivot on the key-less join table whose deleting listener always vetoes. */
    static final class VetoPivot extends Pivot {
        @Override
        protected String table() {
            return ROLE_USER;
        }

        @Override
        protected boolean deleting() {
            return false;
        }
    }
}

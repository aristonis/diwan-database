package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Mechanic;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Owner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The eager load of a has-one-through relation: a parent without an owner gets {@code null} or the
 * default, the owners load in one query, and the first matching owner in database order is kept.
 */
class HasOneThroughEagerTest {

    private ThroughDb db;

    @BeforeEach
    void open() throws SQLException {
        db = ThroughDb.open();
        db.seed();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void mechanicWithoutOwnerGetsNull() {
        // Arrange
        db.log.clear();

        // Act
        ModelCollection<Mechanic> mechanics = Mechanic.QUERY.on(db.db).with("owner").get();
        Mechanic pat = named(mechanics, "Pat");

        // Assert
        assertTrue(pat.relationLoaded("owner"));
        assertNull(pat.getRelation("owner"));
        assertEquals(1, selectsFrom("owners"));
    }

    @Test
    void mechanicWithoutOwnerGetsDefault() {
        // Arrange
        db.log.clear();

        // Act
        ModelCollection<Mechanic> mechanics = Mechanic.QUERY.on(db.db).with("ownerOrDefault").get();
        Owner forPat = named(mechanics, "Pat").related("ownerOrDefault");
        Owner forMo = named(mechanics, "Mo").related("ownerOrDefault");

        // Assert
        assertFalse(forPat.exists());
        assertNull(forPat.getKey());
        assertEquals("Olga", forMo.getAttribute("name"));
        assertEquals(1, selectsFrom("owners"));
    }

    @Test
    void firstMatchWins() {
        // Arrange: Mo services a second car, which also has an owner.
        insert("cars", "id", 2, "mechanic_id", 1, "model", "Golf");
        insert("owners", "id", 2, "car_id", 2, "name", "Otto");
        db.log.clear();

        // Act
        ModelCollection<Mechanic> mechanics = Mechanic.QUERY.on(db.db).with("owner").get();
        Owner owner = named(mechanics, "Mo").related("owner");

        // Assert: the owner of the first car in database order is kept.
        assertEquals("Olga", owner.getAttribute("name"));
    }

    private int selectsFrom(String table) {
        int count = 0;
        for (String sql : db.log.sql()) {
            if (sql.contains("from \"" + table + "\"")) {
                count++;
            }
        }
        return count;
    }

    private static Mechanic named(ModelCollection<Mechanic> mechanics, String name) {
        for (Mechanic mechanic : mechanics) {
            if (name.equals(mechanic.getAttribute("name"))) {
                return mechanic;
            }
        }
        throw new AssertionError("no mechanic named " + name);
    }

    private void insert(String table, Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        db.db.table(table).insert(row);
    }
}

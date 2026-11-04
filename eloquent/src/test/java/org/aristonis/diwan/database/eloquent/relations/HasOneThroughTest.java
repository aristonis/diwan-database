package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Car;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Owner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lazy read of a has-one-through relation: the null and default results of a far parent with no
 * key or no owner, the default models built with {@code withDefault}, and the SQL of the read.
 */
class HasOneThroughTest {

    private static final String LAZY_SQL = "select \"owners\".*, \"cars\".\"mechanic_id\" as \"laravel_through_key\""
            + " from \"owners\" inner join \"cars\" on \"cars\".\"id\" = \"owners\".\"car_id\""
            + " where \"cars\".\"mechanic_id\" = ? limit 1";

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
    void unsavedFarParentGivesNull() {
        // Arrange
        ThroughModels.Mechanic mechanic = ThroughModels.Mechanic.QUERY.on(db.db).newModel();

        // Act
        Owner owner = mechanic.owner().getResults();

        // Assert
        assertNull(owner);
        assertTrue(db.log.sql().isEmpty());
    }

    @Test
    void defaultModelHasNoKeySet() {
        // Arrange
        MechanicDefaults mechanic = MechanicDefaults.QUERY.on(db.db).newModel();

        // Act
        Owner owner = mechanic.ownerWithMap().getResults();

        // Assert
        assertEquals("Spare", owner.getAttribute("name"));
        assertNull(owner.getKey());
        assertFalse(owner.exists());
        assertTrue(db.log.sql().isEmpty());
    }

    @Test
    void lazyReadSql() {
        // Arrange
        ThroughModels.Mechanic mechanic = ThroughModels.Mechanic.QUERY.on(db.db).findOrFail(1);
        db.log.clear();

        // Act
        Owner owner = mechanic.owner().getResults();

        // Assert
        assertEquals("Olga", owner.getAttribute("name"));
        assertEquals(Collections.singletonList(LAZY_SQL), db.log.sql());
        assertEquals(Collections.singletonList(1), db.log.bindingCounts());
    }

    @Test
    void withDefaultMapAndCallback() {
        // Arrange: Pat has no car, so no owner matches.
        MechanicDefaults pat = MechanicDefaults.QUERY.on(db.db).findOrFail(2);

        // Act
        Owner fromMap = pat.ownerWithMap().getResults();
        Owner fromCallback = pat.ownerWithCallback().getResults();

        // Assert: the map fills the attributes, and the callback receives the far parent.
        assertEquals("Spare", fromMap.getAttribute("name"));
        assertEquals("For Pat", fromCallback.getAttribute("name"));
        assertNull(fromCallback.getKey());
        assertFalse(fromCallback.exists());
    }

    @Test
    void bareDefaultIsReturnedWhenNoRowMatches() {
        // Arrange
        ThroughModels.Mechanic pat = ThroughModels.Mechanic.QUERY.on(db.db).findOrFail(2);

        // Act
        Owner owner = pat.ownerOrDefault().getResults();

        // Assert
        assertNull(owner.getAttribute("name"));
        assertNull(owner.getKey());
        assertFalse(owner.exists());
    }

    /**
     * A mechanic whose owner is declared with each default form.
     */
    static final class MechanicDefaults extends Model {
        static final ModelQuery<MechanicDefaults> QUERY = ModelQuery.of(MechanicDefaults.class,
                MechanicDefaults::new);

        @Override
        protected String table() {
            return "mechanics";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        HasOneThrough<Owner> ownerWithMap() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "Spare");
            return hasOneThrough(Owner.class, Car.class, "mechanic_id", "car_id", "id", "id")
                    .withDefault(attributes);
        }

        HasOneThrough<Owner> ownerWithCallback() {
            return hasOneThrough(Owner.class, Car.class, "mechanic_id", "car_id", "id", "id")
                    .withDefault((owner, mechanic) -> owner.setAttribute("name",
                            "For " + mechanic.getAttribute("name")));
        }
    }
}

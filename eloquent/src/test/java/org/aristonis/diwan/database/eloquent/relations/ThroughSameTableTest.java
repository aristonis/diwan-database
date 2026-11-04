package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The refusal of a through relation whose related table is its through table, or whose three tables
 * are all the same. The refusal names the relation and happens before any SQL runs; the tables are not
 * created, so a query that got past the refusal would fail on its own.
 */
class ThroughSameTableTest {

    private ThroughDb db;

    @BeforeEach
    void open() throws SQLException {
        db = ThroughDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void relatedEqualsThroughIsRefused() {
        // Arrange
        Hub hub = Hub.QUERY.on(db.db).newModel();
        hub.setAttribute("id", 1);

        // Act
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> hub.getRelationValue("hops"));

        // Assert
        assertTrue(refusal.getMessage().contains("[hops]"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("Hub"), refusal.getMessage());
        assertTrue(db.log.sql().isEmpty());
    }

    @Test
    void allThreeEqualIsRefused() {
        // Arrange
        Hop hop = Hop.QUERY.on(db.db).newModel();
        hop.setAttribute("id", 1);

        // Act
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> hop.getRelationValue("peers"));

        // Assert
        assertTrue(refusal.getMessage().contains("[peers]"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("Hop"), refusal.getMessage());
        assertTrue(db.log.sql().isEmpty());
    }

    /**
     * A parent on its own table, with a through relation to hops, which are also the related table.
     */
    static final class Hub extends Model {
        static final ModelQuery<Hub> QUERY = ModelQuery.of(Hub.class, Hub::new);

        @Override
        protected String table() {
            return "hubs";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Hub.class).add("hops", Hub::hops);
        }

        HasManyThrough<Hop> hops() {
            return hasManyThrough(Hop.class, Hop.class, "hub_id", "id");
        }
    }

    /**
     * A hop, whose peers are reached through hops too, so all three tables are the same.
     */
    static final class Hop extends Model {
        static final ModelQuery<Hop> QUERY = ModelQuery.of(Hop.class, Hop::new);

        @Override
        protected String table() {
            return "hops";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Hop.class).add("peers", Hop::peers);
        }

        HasManyThrough<Hop> peers() {
            return hasManyThrough(Hop.class, Hop.class, "parent_id", "id");
        }
    }
}

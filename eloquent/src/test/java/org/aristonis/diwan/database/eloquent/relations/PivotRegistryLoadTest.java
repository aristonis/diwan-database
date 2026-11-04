package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.internal.UnregisteredModelException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The registry load behind {@code using(...)}: a pivot class whose entry only its static
 * initialiser creates is loaded once and found, and a pivot class with no entry is refused by name.
 *
 * <p>The pivot classes here are nested and named by no other test, so the first lookup of each one
 * is a registry miss whatever order the tests of the module run in.
 */
class PivotRegistryLoadTest {

    /** A pivot whose entry only its static initialiser creates; the class literal does not run it. */
    static final class Untouched extends Pivot {
        static final ModelQuery<Untouched> QUERY = ModelQuery.of(Untouched.class, Untouched::new);

        public Untouched() {
        }

        @Override
        protected String table() {
            return "role_user";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A pivot with no {@code ModelQuery} entry anywhere. */
    static final class NoEntryPivot extends Pivot {
        public NoEntryPivot() {
        }

        @Override
        protected String table() {
            return "role_user";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

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
    void usingClassNotYetInitialisedLoadsAndRetries() {
        BelongsToMany<BtmModels.Role> relation = BtmModels.User.QUERY.on(db.db).newModel().roles();

        BelongsToMany<BtmModels.Role> bound = relation.using(Untouched.class);

        assertEquals(Untouched.class, bound.getPivotClass());
    }

    @Test
    void usingClassWithoutEntryThrowsNamingTheClass() {
        BelongsToMany<BtmModels.Role> relation = BtmModels.User.QUERY.on(db.db).newModel().roles();

        UnregisteredModelException failure = assertThrows(UnregisteredModelException.class,
                () -> relation.using(NoEntryPivot.class));

        assertEquals(NoEntryPivot.class.getName(), failure.getModelClassName());
        assertTrue(failure.getMessage().contains(NoEntryPivot.class.getName()), failure.getMessage());
    }
}

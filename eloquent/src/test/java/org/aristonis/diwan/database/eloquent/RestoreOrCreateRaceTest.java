package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * restoreOrCreate and createOrRestore when a unique index refuses the insert, and when they run
 * inside a transaction that is rolled back.
 */
class RestoreOrCreateRaceTest {

    private static final String UNIQUE_NAMES = "create unique index users_name_unique on users (name)";

    private ScopeFixtures fixtures;

    /**
     * A soft-deleting model on the users table. Its {@code creating()} hook plays the other caller:
     * it inserts a trashed row named Eve after the lookup and before the insert, so the insert hits
     * the unique index.
     */
    static final class RaceUser extends Model {
        private final ScopeFixtures racing;

        RaceUser(ScopeFixtures racing) {
            this.racing = racing;
        }

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected boolean creating() {
            racing.insert("users", "id", 4, "name", "Eve", "deleted_at", ScopeFixtures.TRASHED_AT);
            return true;
        }
    }

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

    private void trash(String table, int id) {
        fixtures.real.statement("update \"" + table + "\" set \"deleted_at\" = '" + ScopeFixtures.TRASHED_AT
                + "' where \"id\" = " + id);
    }

    private long count(String table, String where) {
        List<Map<String, Object>> rows = fixtures.real.select(
                "select count(*) as c from " + table + " where " + where, Collections.emptyList());
        return ((Number) rows.get(0).get("c")).longValue();
    }

    private static long keyOf(Model model) {
        return Long.parseLong(String.valueOf(model.getKey()));
    }

    @Test
    void restoreOrCreateRecoversFromRowInsertedAfterTheLookup() {
        fixtures.real.statement(UNIQUE_NAMES);
        ModelQuery<RaceUser> racing = ModelQuery.of(RaceUser.class, () -> new RaceUser(fixtures))
                .on(fixtures.db).withClock(ModelClock.fixed(ScopeFixtures.NOW));

        RaceUser found = racing.query().restoreOrCreate(Collections.singletonMap("name", "Eve"),
                Collections.singletonMap("country_id", 2));

        assertEquals(4L, keyOf(found));
        assertFalse(found.trashed());
        assertFalse(found.wasRecentlyCreated());
        assertEquals(1, count("users", "\"name\" = 'Eve' and \"deleted_at\" is null"));
        assertEquals(1, count("users", "\"name\" = 'Eve'"));
    }

    @Test
    void createOrRestoreInsideTransactionRecoversFromUniqueViolation() {
        fixtures.real.statement(UNIQUE_NAMES);
        trash("users", 1);

        User found = fixtures.db.connection().transaction(c -> fixtures.users().on(c).query()
                .createOrRestore(Collections.singletonMap("name", "Ann"), Collections.singletonMap("country_id", 2)));

        assertEquals(1L, keyOf(found));
        assertFalse(found.trashed());
        assertEquals(1, count("users", "\"name\" = 'Ann' and \"deleted_at\" is null"));
        assertEquals(3, count("users", "\"id\" > 0"));
    }

    @Test
    void createOrRestoreRollsBackInsideTransaction() {
        fixtures.real.statement(UNIQUE_NAMES);
        trash("users", 1);

        assertThrows(IllegalStateException.class, () -> fixtures.db.connection().transaction(c -> {
            User found = fixtures.users().on(c).query()
                    .createOrRestore(Collections.singletonMap("name", "Ann"),
                            Collections.singletonMap("country_id", 2));
            assertFalse(found.trashed());
            throw new IllegalStateException("roll back");
        }));

        assertEquals(1, count("users", "\"id\" = 1 and \"deleted_at\" = '" + ScopeFixtures.TRASHED_AT + "'"));
        assertEquals(3, count("users", "\"id\" > 0"));
    }

    @Test
    void restoreOrCreateRollsBackInsideTransaction() {
        trash("users", 1);

        assertThrows(IllegalStateException.class, () -> fixtures.db.connection().transaction(c -> {
            User found = fixtures.users().on(c).query().restoreOrCreate(Collections.singletonMap("name", "Ann"));
            assertFalse(found.trashed());
            throw new IllegalStateException("roll back");
        }));

        assertEquals(1, count("users", "\"id\" = 1 and \"deleted_at\" = '" + ScopeFixtures.TRASHED_AT + "'"));
        assertEquals(3, count("users", "\"id\" > 0"));
    }
}

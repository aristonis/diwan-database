package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.junit.jupiter.api.Test;

/** saveOrFail and binding a model query to a transaction's connection. */
class ModelTransactionTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123456Z");

    private final FakeDb fake = new FakeDb();

    /** A non-incrementing model with no timestamps and no generated key. */
    static final class Tag extends Model {
        static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

        @Override
        protected boolean incrementing() {
            return false;
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A user whose saving hook records the transaction level it runs at. */
    static final class LevelUser extends User {
        static final ModelQuery<LevelUser> QUERY = ModelQuery.of(LevelUser.class, LevelUser::new);
        int levelSeen = -1;

        @Override
        protected boolean saving() {
            levelSeen = getConnection().transactionLevel();
            return true;
        }
    }

    @Test
    void saveOrFailSavesInsideATransaction() {
        LevelUser user = LevelUser.QUERY.on(fake.db).withClock(ModelClock.fixed(NOW)).make(row("name", "Ann"));

        assertTrue(user.saveOrFail());

        assertEquals(1, user.levelSeen);
        assertTrue(user.exists());
        assertTrue(user.isClean());
        assertEquals(1, fake.calls.size());
    }

    @Test
    void saveOrFailReturnsFalseWhenAHookVetoes() {
        User vetoing = new User() {
            @Override
            protected boolean saving() {
                return false;
            }
        };
        vetoing.setConnection(fake.db);

        assertFalse(vetoing.saveOrFail());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void saveOrFailThrowsWhenANonIncrementingInsertHasNothingToInsert() {
        Tag tag = Tag.QUERY.on(fake.db).newModel();

        IllegalStateException e = assertThrows(IllegalStateException.class, tag::saveOrFail);

        assertEquals("Cannot insert model [" + Tag.class.getName() + "]: it has no attributes and no key",
                e.getMessage());
        assertFalse(tag.exists());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void saveStillReturnsTrueForAnEmptyNonIncrementingInsert() {
        Tag tag = Tag.QUERY.on(fake.db).newModel();

        assertTrue(tag.save());

        assertFalse(tag.exists());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void saveOrFailLeavesTheModelBoundToItsOwnFactory() {
        Tag tag = Tag.QUERY.on(fake.db).make(row("name", "a"));

        assertTrue(tag.saveOrFail());

        assertSame(fake.db, tag.getQueryFactory());
        assertEquals("insert into \"tags\" (\"name\") values (?)", fake.last().sql());
    }

    @Test
    void onConnectionKeepsTheGrammarAndProcessorOfTheBoundFactory() {
        FakeDb other = new FakeDb();

        User user = User.QUERY.on(fake.db).on(other.connection).newModel();

        assertSame(other.connection, user.getConnection());
        assertSame(fake.db.grammar(), user.getQueryFactory().grammar());
        assertSame(fake.db.processor(), user.getQueryFactory().processor());
    }

    @Test
    void onConnectionTakesTheGrammarOfTheResolvedFactory() {
        FakeDb other = new FakeDb();
        ConnectionResolver resolver = new ConnectionResolver().register("default", fake.db);

        User user = User.QUERY.using(resolver).withClock(ModelClock.fixed(NOW)).on(other.connection).newModel();
        user.fill(row("name", "Ann")).save();

        assertSame(fake.db.grammar(), user.getQueryFactory().grammar());
        assertTrue(fake.calls.isEmpty());
        assertEquals(1, other.calls.size());
    }

    @Test
    void onConnectionRunsInsideTheCallbacksTransaction() {
        ModelQuery<User> users = User.QUERY.on(fake.db);
        fake.returns("select * from \"users\" where \"users\".\"id\" = ? limit 1", row("id", 1L, "name", "Ann"));

        User found = fake.connection.transaction((Connection c) -> users.on(c).find(1).get());

        assertEquals("Ann", found.getAttribute("name"));
    }

    @Test
    void onConnectionRejectsNull() {
        assertThrows(NullPointerException.class, () -> User.QUERY.on((Connection) null));
    }

    @Test
    void onConnectionWithNoFactoryToCopyFails() {
        FakeDb other = new FakeDb();
        ConnectionResolver.resetDefault();

        assertThrows(IllegalStateException.class, () -> User.QUERY.on(other.connection));
    }
}

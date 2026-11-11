package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.aristonis.diwan.database.schema.ForeignKeySource.KeyKind;
import org.junit.jupiter.api.Test;

/** What {@link ModelKeys} reads from a model: names, table and the kind of key. */
class ModelKeysTest {

    /** A model with every default: integer key {@code id}, table {@code users}. */
    static final class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);
    }

    /** A model with its own table and key column. */
    static final class Member extends Model {
        static final ModelQuery<Member> QUERY = ModelQuery.of(Member.class, Member::new);

        @Override
        protected String table() {
            return "members";
        }

        @Override
        protected String primaryKey() {
            return "member_no";
        }
    }

    /** A model keyed by a generated UUID. */
    static final class Doc extends Model {
        static final ModelQuery<Doc> QUERY = ModelQuery.of(Doc.class, Doc::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.UUID;
        }
    }

    /** A model keyed by a generated ULID. */
    static final class Ticket extends Model {
        static final ModelQuery<Ticket> QUERY = ModelQuery.of(Ticket.class, Ticket::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.ULID;
        }
    }

    /** A model with a plain string key. */
    static final class Slug extends Model {
        static final ModelQuery<Slug> QUERY = ModelQuery.of(Slug.class, Slug::new);

        @Override
        protected String keyType() {
            return "string";
        }
    }

    /** A model without a primary key. */
    static final class Log extends Model {
        static final ModelQuery<Log> QUERY = ModelQuery.of(Log.class, Log::new);

        @Override
        protected String primaryKey() {
            return null;
        }
    }

    @Test
    void stringKeyRefused() {
        IllegalArgumentException byClass =
                assertThrows(IllegalArgumentException.class, () -> ModelKeys.of(Slug.class));
        assertThrows(IllegalArgumentException.class, () -> ModelKeys.of(new Slug()));

        assertEquals("Model " + Slug.class.getName()
                + " has a string key; foreignIdFor supports integer, UUID and ULID keys", byClass.getMessage());
    }

    @Test
    void readsTheModelConfig() {
        ModelKeys keys = ModelKeys.of(User.class);

        assertEquals("user_id", keys.foreignKey());
        assertEquals("users", keys.table());
        assertEquals("id", keys.keyName());
        assertEquals(KeyKind.INTEGER, keys.keyKind());
    }

    @Test
    void readsCustomTableAndKey() {
        ModelKeys keys = ModelKeys.of(Member.class);

        assertEquals("member_member_no", keys.foreignKey());
        assertEquals("members", keys.table());
        assertEquals("member_no", keys.keyName());
        assertEquals(KeyKind.INTEGER, keys.keyKind());
    }

    @Test
    void uuidModelGivesUuidKind() {
        assertEquals(KeyKind.UUID, ModelKeys.of(Doc.class).keyKind());
    }

    @Test
    void ulidModelGivesUlidKind() {
        assertEquals(KeyKind.ULID, ModelKeys.of(Ticket.class).keyKind());
    }

    @Test
    void tableIsUnprefixed() {
        assertEquals("members", ModelKeys.of(new Member()).table());
    }

    @Test
    void readsAModelInstance() {
        ModelKeys keys = ModelKeys.of(new Ticket());

        assertEquals("ticket_id", keys.foreignKey());
        assertEquals("tickets", keys.table());
        assertEquals("id", keys.keyName());
        assertEquals(KeyKind.ULID, keys.keyKind());
    }

    @Test
    void modelWithoutPrimaryKeyIsRefused() {
        IllegalArgumentException byClass =
                assertThrows(IllegalArgumentException.class, () -> ModelKeys.of(Log.class));
        assertThrows(IllegalArgumentException.class, () -> ModelKeys.of(new Log()));

        assertEquals("Model " + Log.class.getName() + " has no primary key", byClass.getMessage());
    }

    @Test
    void nullArgumentsAreRefused() {
        assertThrows(NullPointerException.class, () -> ModelKeys.of((Class<? extends Model>) null));
        assertThrows(NullPointerException.class, () -> ModelKeys.of((Model) null));
    }
}

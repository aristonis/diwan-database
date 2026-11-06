package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.UniqueConstraintViolationException;
import org.aristonis.diwan.database.eloquent.casts.Attribute;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Finders and creators with enum values, withCasts, unique-id keys and a date column that has its own cast. */
class ModelFinderRulesTest {

    /** A plain enum used as an attribute and as a where value. */
    enum Status { DRAFT, PUBLISHED }

    /** A plain enum used as a string key. */
    enum Code { A, B, MISSING }

    /** An account on a table with a status column. */
    static final class Account extends Model {
        static final ModelQuery<Account> QUERY = ModelQuery.of(Account.class, Account::new);

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A label keyed by a text code. */
    static final class Label extends Model {
        static final ModelQuery<Label> QUERY = ModelQuery.of(Label.class, Label::new);

        @Override
        protected String primaryKey() {
            return "code";
        }

        @Override
        protected String keyType() {
            return "string";
        }

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

    /** A ticket keyed by a generated UUID. */
    static final class Ticket extends Model {
        static final ModelQuery<Ticket> QUERY = ModelQuery.of(Ticket.class, Ticket::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.UUID;
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

    /** A slip keyed by a generated ULID. */
    static final class Slip extends Model {
        static final ModelQuery<Slip> QUERY = ModelQuery.of(Slip.class, Slip::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.ULID;
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

    /** A soft-deleting model whose deleted_at column carries its own cast. */
    static final class CastTrashed extends Model {
        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Collections.<String, Cast<?>>singletonMap("deleted_at", Casts.bool());
        }
    }

    /** A soft-deleting model whose deleted_at column is read through an accessor. */
    static final class AccessorTrashed extends Model {
        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected Map<String, Attribute<?>> accessors() {
            return Collections.<String, Attribute<?>>singletonMap("deleted_at",
                    Attribute.<String>get((model, value, attributes) -> "expected"));
        }
    }

    /** A soft-deleting model whose deleted_at column is cast to text. */
    static final class StringTrashed extends Model {
        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Collections.<String, Cast<?>>singletonMap("deleted_at", Casts.string());
        }
    }

    private static final String UUID_FORM = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String ULID_FORM = "[0-9a-z]{26}";

    private SqliteModelDb database;
    private ModelQuery<Account> accounts;

    @BeforeEach
    void open() throws Exception {
        database = SqliteModelDb.open();
        database.sql("create table accounts (id integer primary key autoincrement, name text, email text unique,"
                + " votes integer, status text, created_at text, updated_at text)");
        database.sql("create table labels (code text primary key, title text)");
        database.sql("create table tickets (id text primary key, title text unique)");
        database.sql("create table slips (id text primary key, title text)");
        accounts = Account.QUERY.on(database.db);
    }

    @AfterEach
    void close() throws Exception {
        database.close();
    }

    @Test
    void anUncastAttributeSetToAnEnumIsStoredByItsName() {
        Account account = accounts.create(row("name", "Ann", "email", "a@x", "status", Status.DRAFT));
        assertEquals("DRAFT", database.scalar("select status from accounts where id = 1"));

        account.setAttribute("status", Status.PUBLISHED);
        account.save();

        assertEquals("PUBLISHED", database.scalar("select status from accounts where id = 1"));
        assertEquals("PUBLISHED", accounts.findOrFail(1).getAttribute("status"));
    }

    @Test
    void finderCreatorsAcceptEnumAttributeValues() {
        Account blank = accounts.firstOrNew(row("status", Status.DRAFT), row());
        Account created = accounts.firstOrCreate(row("status", Status.DRAFT), row("name", "a", "email", "a@x"));
        Account again = accounts.firstOrCreate(row("status", Status.DRAFT), row("name", "b", "email", "b@x"));
        Account raced = accounts.query().createOrFirst(row("email", "a@x"),
                row("name", "z", "status", Status.PUBLISHED));

        assertFalse(blank.exists());
        assertEquals(Status.DRAFT, blank.getAttribute("status"));
        assertTrue(created.wasRecentlyCreated());
        assertTrue(again.is(created));
        assertTrue(raced.is(created));
        assertEquals(1L, ((Number) database.scalar("select count(*) from accounts")).longValue());
        assertEquals("DRAFT", database.scalar("select status from accounts where id = 1"));
    }

    @Test
    void enumsWorkAsWhereAndInsertValues() {
        database.db.table("accounts").insert(row("name", "n", "email", "e@x", "status", Status.PUBLISHED));

        assertEquals(1L, accounts.where("status", Status.PUBLISHED).count());
        assertEquals(0L, accounts.where("status", Status.DRAFT).count());
        assertEquals("PUBLISHED", database.scalar("select status from accounts where id = 1"));
    }

    @Test
    void enumIdsAreFoundAndMissingOnesFail() {
        database.sql("insert into labels (code, title) values ('A', 'first'), ('B', 'second')");
        ModelQuery<Label> labels = Label.QUERY.on(database.db);

        assertTrue(labels.find(Code.A).isPresent());
        assertEquals(2, labels.query().findOrFail(Arrays.asList(Code.A, Code.B)).size());
        assertThrows(ModelNotFoundException.class, () -> labels.findOrFail(Code.MISSING));
        assertThrows(ModelNotFoundException.class,
                () -> labels.query().findOrFail(Arrays.asList(Code.A, Code.MISSING)));
    }

    @Test
    void createOrFirstRethrowsAViolationOnAFieldOutsideTheSearchAttributes() {
        database.sql("insert into accounts (name, email) values ('Other', 'dup@x')");

        assertThrows(UniqueConstraintViolationException.class,
                () -> accounts.query().createOrFirst(row("name", "Mine"), row("email", "dup@x")));

        assertEquals(1L, ((Number) database.scalar("select count(*) from accounts")).longValue());
    }

    @Test
    void firstOrNewReadsTheFoundModelThroughWithCasts() {
        EloquentBuilder<Account> query = accounts.query().withCasts(row("votes", "string"));
        assertFalse(query.firstOrNew(row("email", "a@x")).exists());
        database.sql("insert into accounts (name, email, votes) values ('Ann', 'a@x', 5)");

        Account found = accounts.query().withCasts(row("votes", "string")).firstOrNew(row("email", "a@x"));

        assertTrue(found.exists());
        assertEquals("5", found.getAttribute("votes"));
    }

    @Test
    void firstOrCreateReadsTheFoundModelThroughWithCasts() {
        Account first = accounts.query().withCasts(row("votes", "string"))
                .firstOrCreate(row("email", "b@x"), row("name", "B", "votes", 3));

        Account second = accounts.query().withCasts(row("votes", "string"))
                .firstOrCreate(row("email", "b@x"), row("name", "B", "votes", 3));

        assertEquals(((Number) first.getKey()).longValue(), ((Number) second.getKey()).longValue());
        assertEquals("3", second.getAttribute("votes"));
    }

    @Test
    void createOrFirstReadsTheFoundModelThroughWithCasts() {
        Account first = accounts.query().withCasts(row("votes", "string"))
                .createOrFirst(row("email", "c@x"), row("name", "C", "votes", 4));

        Account second = accounts.query().withCasts(row("votes", "string"))
                .createOrFirst(row("email", "c@x"), row("name", "C", "votes", 4));

        assertEquals(((Number) first.getKey()).longValue(), ((Number) second.getKey()).longValue());
        assertEquals("4", second.getAttribute("votes"));
    }

    @Test
    void aQuietSaveGeneratesAUuidKey() {
        Ticket ticket = Ticket.QUERY.on(database.db).make(row("title", "a"));

        boolean saved = ticket.saveQuietly();

        assertTrue(saved);
        assertTrue(ticket.getString("id").matches(UUID_FORM), ticket.getString("id"));
        assertEquals(ticket.getKey(), database.scalar("select id from tickets"));
    }

    @Test
    void aQuietSaveGeneratesAUlidKey() {
        Slip slip = Slip.QUERY.on(database.db).make(row("title", "a"));

        boolean saved = slip.saveQuietly();

        assertTrue(saved);
        assertTrue(slip.getString("id").matches(ULID_FORM), slip.getString("id"));
        assertEquals(slip.getKey(), database.scalar("select id from slips"));
    }

    @Test
    void createOrFirstOnAUuidModelReturnsTheExistingRow() {
        ModelQuery<Ticket> tickets = Ticket.QUERY.on(database.db);
        Ticket first = tickets.query().createOrFirst(row("title", "a"), row());

        Ticket second = tickets.query().createOrFirst(row("title", "a"), row());

        assertTrue(first.wasRecentlyCreated());
        assertFalse(second.wasRecentlyCreated());
        assertEquals(first.getKey(), second.getKey());
        assertEquals(1L, ((Number) database.scalar("select count(*) from tickets")).longValue());
    }

    @Test
    void chunkByIdPagesAStringKeyWithoutRepeatsOrGaps() {
        ModelQuery<Ticket> tickets = Ticket.QUERY.on(database.db);
        for (int i = 1; i <= 5; i++) {
            tickets.create(row("title", "t" + i));
        }
        List<Object> keys = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();

        tickets.query().chunkById(2, chunk -> {
            sizes.add(chunk.size());
            keys.addAll(chunk.modelKeys());
            return true;
        });

        assertEquals(Arrays.asList(2, 2, 1), sizes);
        assertEquals(5, keys.size());
        List<Object> sorted = new ArrayList<>(keys);
        sorted.sort(null);
        assertEquals(sorted, keys);
        assertEquals(5, new java.util.HashSet<>(keys).size());
    }

    @Test
    void lazyByIdWalksAStringKeyOnce() {
        ModelQuery<Ticket> tickets = Ticket.QUERY.on(database.db);
        for (int i = 1; i <= 5; i++) {
            tickets.create(row("title", "t" + i));
        }
        List<Object> keys = new ArrayList<>();

        for (Ticket ticket : tickets.query().lazyById(2)) {
            keys.add(ticket.getKey());
        }

        assertEquals(5, new java.util.HashSet<>(keys).size());
        assertEquals(5, keys.size());
    }

    @Test
    void aCastOnDeletedAtWinsOverAnyAutomaticDateRead() {
        CastTrashed model = new CastTrashed();

        model.setRawAttributes(row("deleted_at", "1"), true);

        assertEquals(Boolean.TRUE, model.getAttribute("deleted_at"));
    }

    @Test
    void anAccessorOnDeletedAtWinsOverAnyAutomaticDateRead() {
        AccessorTrashed model = new AccessorTrashed();

        model.setRawAttributes(row("deleted_at", "2018-12-29 13:59:39"), true);

        assertEquals("expected", model.getAttribute("deleted_at"));
    }

    @Test
    void aStringCastOnDeletedAtKeepsTheStoredText() {
        StringTrashed model = new StringTrashed();

        model.setRawAttributes(row("deleted_at", "2018-12-29 13:59:39"), true);

        assertEquals("2018-12-29 13:59:39", model.getAttribute("deleted_at"));
        assertTrue(model.trashed());
    }
}

package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.exception.MassAssignmentException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelClock;
import org.aristonis.diwan.database.query.QueryCursor;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.TimestampFormat;
import org.aristonis.diwan.database.eloquent.UniqueIdKind;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Models over Android's SQLiteDatabase, under Robolectric. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidModelTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123456Z");

    /** A text-timestamp model on a table built with timestamps(). */
    public static final class Account extends Model {
        static final ModelQuery<Account> QUERY = ModelQuery.of(Account.class, Account::new);

        @Override
        protected List<String> fillable() {
            return List.of("name", "email", "votes");
        }
    }

    /** A ULID-keyed model whose timestamps are instants, on instant columns. */
    public static final class Reading extends Model {
        static final ModelQuery<Reading> QUERY = ModelQuery.of(Reading.class, Reading::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.ULID;
        }

        @Override
        protected TimestampFormat timestampFormat() {
            return TimestampFormat.INSTANT;
        }

        @Override
        protected List<String> guarded() {
            return List.of();
        }
    }

    /** A model with every default: everything guarded. */
    public static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);
    }

    private SQLiteDatabase database;
    private QueryFactory db;
    private ModelQuery<Account> accounts;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        Connection connection = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("accounts", t -> {
            t.id();
            t.string("name");
            t.string("email").unique();
            t.integer("votes").nullable();
            t.timestamps();
        });
        SQLiteSchema.of(connection).create("readings", t -> {
            t.ulid("id").primary();
            t.integer("value");
            t.instant("created_at").nullable();
            t.instant("updated_at").nullable();
        });
        accounts = Account.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    public void crudWithTextTimestamps() {
        Account created = accounts.create(row("name", "Ann", "email", "a@x"));

        Account found = accounts.findOrFail(1);
        assertEquals("2026-11-02 10:15:30", found.getAttribute("created_at"));
        assertEquals(Instant.parse("2026-11-02T10:15:30Z"), found.getInstant("updated_at"));
        assertTrue(found.isClean());

        found.setAttribute("name", "Bob");
        assertTrue(found.save());
        assertEquals("Bob", accounts.findOrFail(1).getAttribute("name"));
        assertTrue(found.delete());
        assertFalse(accounts.find(created.getKey()).isPresent());
    }

    @Test
    public void readBackIdTypeStillMatchesTheInsertedKey() {
        Account created = accounts.create(row("name", "Ann", "email", "a@x"));
        Account found = accounts.findOrFail(1);

        System.out.println("Android model ids: created " + created.getKey().getClass().getSimpleName()
                + ", read " + found.getKey().getClass().getSimpleName());
        assertEquals(Long.valueOf(1), found.getLong("id"));
        assertTrue(found.is(created));
        assertTrue(created.is(found));
        assertTrue(accounts.find(found.getKey()).isPresent());
        assertTrue(accounts.find(1L).isPresent());
        found.setAttribute("id", 1L);
        assertTrue(found.isClean());
    }

    @Test
    public void instantTimestampsOnInstantColumns() {
        ModelQuery<Reading> readings = Reading.QUERY.on(db).withClock(ModelClock.fixed(NOW));
        Reading reading = readings.create(row("value", 3));

        Reading found = readings.findOrFail(reading.getKey());

        assertEquals(26, reading.getString("id").length());
        assertEquals(NOW, found.getInstant("created_at"));
        found.setAttribute("updated_at", NOW);
        assertTrue(found.isClean());
    }

    @Test
    public void massAssignmentRefusesGuardedKeysAndForceFillBypasses() {
        MassAssignmentException e = assertThrows(MassAssignmentException.class,
                () -> Post.QUERY.on(db).make(row("title", "x")));
        Account forced = accounts.forceCreate(row("name", "F", "email", "f@x", "id", 40));

        assertTrue(e.getMessage().startsWith("Add [title] to fillable property"));
        assertEquals(Long.valueOf(40), accounts.findOrFail(40).getLong("id"));
        assertTrue(forced.exists());
    }

    @Test
    public void cursorAndTransactionYieldModels() {
        for (int i = 1; i <= 5; i++) {
            accounts.create(row("name", "user" + i, "email", "u" + i + "@x", "votes", i));
        }
        List<Object> streamed = new ArrayList<>();
        try (QueryCursor<Account> cursor = accounts.query().orderBy("id").cursor()) {
            for (Account account : cursor) {
                assertTrue(account.exists());
                streamed.add(account.getLong("id"));
            }
        }

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), streamed);
        assertEquals(5, accounts.query().count());
        assertThrows(IllegalStateException.class, () -> db.connection().transaction(c -> {
            accounts.on(c).create(row("name", "Gone", "email", "gone@x"));
            throw new IllegalStateException("roll back");
        }));
        assertEquals(5, accounts.query().count());
    }
}

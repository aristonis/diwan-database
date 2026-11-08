package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** fillAndInsert, fillAndInsertOrIgnore and fillAndInsertGetId: fill, casts, ids, timestamps, one statement. */
class FillAndInsertTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30Z");
    private static final String NOW_TEXT = "2026-11-02 10:15:30";

    /** A member with casts, timestamps as text and a fillable list that leaves out {@code role}. */
    static final class Member extends Model {
        static final ModelQuery<Member> QUERY = ModelQuery.of(Member.class, Member::new);

        @Override
        protected List<String> fillable() {
            return List.of("email", "score", "born");
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("score", Casts.integer(), "born", Casts.date());
        }
    }

    /** A UUID-keyed model. */
    static final class Ticket extends Model {
        static final ModelQuery<Ticket> QUERY = ModelQuery.of(Ticket.class, Ticket::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.UUID;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A UUID-keyed model whose write check records the key and created_at it sees. */
    static final class Watcher extends Model {
        static final ModelQuery<Watcher> QUERY = ModelQuery.of(Watcher.class, Watcher::new);
        static final List<Object[]> seen = new java.util.ArrayList<>();

        @Override
        protected String table() {
            return "tickets";
        }

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.UUID;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected void beforeWrite() {
            seen.add(new Object[] {getAttribute("id"), getAttribute("created_at")});
        }
    }

    /** A model whose write check refuses the title {@code bad}. */
    static final class Strict extends Model {
        static final ModelQuery<Strict> QUERY = ModelQuery.of(Strict.class, Strict::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected void beforeWrite() {
            if ("bad".equals(getAttribute("title"))) {
                throw new IllegalStateException("refused");
            }
        }
    }

    /** A model with listeners registered by the test that checks events. */
    static final class Audited extends Model {
        static final ModelQuery<Audited> QUERY = ModelQuery.of(Audited.class, Audited::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private SqliteModelDb database;
    private QueryFactory db;
    private final AtomicInteger statements = new AtomicInteger();

    @BeforeEach
    void open() throws SQLException {
        database = SqliteModelDb.open();
        db = database.db;
        database.sql("create table members (id integer primary key autoincrement, email text unique, role text,"
                + " score integer, born text, created_at text, updated_at text)");
        database.sql("create table tickets (id text primary key, code text unique, created_at text, updated_at text)");
        database.sql("create table stricts (id integer primary key autoincrement, title text)");
        database.sql("create table auditeds (id integer primary key autoincrement, title text)");
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private ModelQuery<Member> members() {
        return Member.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    private long count(String table) {
        return ((Number) database.scalar("select count(*) from " + table)).longValue();
    }

    @Test
    void fillsCastsAndTimestampsInOneStatement() {
        database.connection.events().listen(e -> statements.incrementAndGet());

        boolean inserted = members().query().fillAndInsert(List.of(
                row("email", "a@x", "score", "7", "born", LocalDate.of(2026, 1, 5)),
                row("email", "b@x", "score", "8", "born", null, "created_at", "2020-01-01 00:00:00",
                        "updated_at", "2020-01-02 00:00:00"),
                row("email", "c@x", "score", "9", "born", null)));

        assertTrue(inserted);
        assertEquals(1, statements.get());
        assertEquals(3L, count("members"));
        assertEquals(7L, ((Number) database.scalar("select score from members where email = 'a@x'")).longValue());
        assertTrue(String.valueOf(database.scalar("select born from members where email = 'a@x'"))
                .startsWith("2026-01-05"));
        assertEquals("2020-01-01 00:00:00", database.scalar("select created_at from members where email = 'b@x'"));
        assertEquals("2020-01-02 00:00:00", database.scalar("select updated_at from members where email = 'b@x'"));
        assertEquals(NOW_TEXT, database.scalar("select created_at from members where email = 'a@x'"));
        assertEquals(NOW_TEXT, database.scalar("select updated_at from members where email = 'c@x'"));
    }

    @Test
    void addsUniqueIds() {
        Ticket.QUERY.on(db).query().fillAndInsert(List.of(row("code", "A"), row("code", "B")));

        List<Map<String, Object>> rows = database.rows("select id from tickets");
        assertEquals(2, rows.size());
        assertNotNull(rows.get(0).get("id"));
        assertEquals(36, String.valueOf(rows.get(0).get("id")).length());
        assertTrue(!rows.get(0).get("id").equals(rows.get(1).get("id")));
    }

    @Test
    void ignoresFillable() {
        members().query().fillAndInsert(List.of(row("email", "a@x", "role", "admin")));

        assertEquals("admin", database.scalar("select role from members"));
    }

    @Test
    void orIgnoreSkipsDuplicates() {
        members().query().fillAndInsert(List.of(row("email", "a@x")));

        int inserted = members().query().fillAndInsertOrIgnore(List.of(row("email", "a@x"), row("email", "b@x")));

        assertEquals(1, inserted);
        assertEquals(2L, count("members"));
    }

    @Test
    void plainInsertRaisesOnDuplicate() {
        members().query().fillAndInsert(List.of(row("email", "a@x")));

        assertThrows(UniqueConstraintViolationException.class,
                () -> members().query().fillAndInsert(List.of(row("email", "a@x"))));
    }

    @Test
    void getIdReturnsTheKey() {
        Object first = members().query().fillAndInsertGetId(row("email", "a@x"));
        Object second = members().query().fillAndInsertGetId(row("email", "b@x"));

        assertEquals(1L, ((Number) first).longValue());
        assertEquals(2L, ((Number) second).longValue());
    }

    @Test
    void getIdReturnsTheUniqueKey() {
        Object id = Ticket.QUERY.on(db).query().fillAndInsertGetId(row("code", "A"));

        assertNotNull(id);
        assertEquals(id, database.scalar("select id from tickets where code = 'A'"));
    }

    @Test
    void beforeWriteRefusalStopsTheInsert() {
        ModelQuery<Strict> strict = Strict.QUERY.on(db);

        assertThrows(IllegalStateException.class,
                () -> strict.query().fillAndInsert(List.of(row("title", "ok"), row("title", "bad"))));

        assertEquals(0L, count("stricts"));
    }

    @Test
    void firesNoEvents() {
        AtomicInteger fired = new AtomicInteger();
        Audited.QUERY.events().creating(m -> fired.incrementAndGet() >= 0);
        Audited.QUERY.events().saving(m -> fired.incrementAndGet() >= 0);
        Audited.QUERY.events().saved(m -> fired.incrementAndGet());

        Audited.QUERY.on(db).query().fillAndInsert(List.of(row("title", "a")));
        Audited.QUERY.on(db).query().fillAndInsertGetId(row("title", "b"));

        assertEquals(0, fired.get());
        assertEquals(2L, count("auditeds"));
    }

    @Test
    void emptyListRunsNothing() {
        database.connection.events().listen(e -> statements.incrementAndGet());

        assertTrue(members().query().fillAndInsert(List.of()));
        assertEquals(0, members().query().fillAndInsertOrIgnore(List.of()));

        assertEquals(0, statements.get());
    }

    @Test
    void beforeWriteSeesKeyAndTimestamps() {
        Watcher.seen.clear();

        Watcher.QUERY.on(db).query().fillAndInsert(List.of(row("code", "A"), row("code", "B")));

        assertEquals(2, Watcher.seen.size());
        for (Object[] pair : Watcher.seen) {
            assertNotNull(pair[0]);
            assertNotNull(pair[1]);
        }
    }

    @Test
    void getIdReplacesNullKey() {
        Object id = Ticket.QUERY.on(db).query().fillAndInsertGetId(row("id", null, "code", "A"));

        assertNotNull(id);
        assertEquals(id, database.scalar("select id from tickets where code = 'A'"));
    }
}

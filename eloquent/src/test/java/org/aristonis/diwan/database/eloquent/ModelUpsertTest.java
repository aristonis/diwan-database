package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Upsert and insertOrIgnore through a model: ids, timestamps and the update list. */
class ModelUpsertTest {

    private static final Instant FIRST = Instant.parse("2026-11-02T10:15:30Z");
    private static final Instant SECOND = Instant.parse("2026-11-03T08:00:00Z");

    /** A model on a table with created_at and updated_at as text. */
    static final class Member extends Model {
        static final ModelQuery<Member> QUERY = ModelQuery.of(Member.class, Member::new);

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
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

    /** A model without timestamps. */
    static final class Tag extends Model {
        static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

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

    @BeforeEach
    void open() throws SQLException {
        database = SqliteModelDb.open();
        db = database.db;
        database.sql("create table members (id integer primary key autoincrement, email text unique, name text,"
                + " created_at text, updated_at text)");
        database.sql("create table tickets (id text primary key, code text unique, title text,"
                + " created_at text, updated_at text)");
        database.sql("create table tags (id integer primary key autoincrement, label text unique, note text)");
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

    private ModelQuery<Member> members(Instant now) {
        return Member.QUERY.on(db).withClock(ModelClock.fixed(now));
    }

    @Test
    void aSecondUpsertUpdatesUpdatedAtAndLeavesCreatedAt() {
        members(FIRST).query().upsert(List.of(row("email", "a@x", "name", "A"), row("email", "b@x", "name", "B")),
                List.of("email"));
        members(SECOND).query().upsert(List.of(row("email", "a@x", "name", "A2")), List.of("email"));

        Map<String, Object> a = database.rows("select * from members where email = 'a@x'").get(0);
        assertEquals("A2", a.get("name"));
        assertEquals("2026-11-02 10:15:30", a.get("created_at"));
        assertEquals("2026-11-03 08:00:00", a.get("updated_at"));
        assertEquals(2L, ((Number) database.scalar("select count(*) from members")).longValue());
        assertEquals("2026-11-02 10:15:30", database.scalar("select updated_at from members where email = 'b@x'"));
    }

    @Test
    void oneMomentServesEveryRowAndACallerValueWins() {
        members(FIRST).query().upsert(List.of(row("email", "a@x", "name", "A", "created_at", "2020-01-01 00:00:00"),
                row("email", "b@x", "name", "B", "created_at", "2021-01-01 00:00:00")), List.of("email"));

        assertEquals("2020-01-01 00:00:00", database.scalar("select created_at from members where email = 'a@x'"));
        assertEquals("2021-01-01 00:00:00", database.scalar("select created_at from members where email = 'b@x'"));
        assertEquals(1L, ((Number) database.scalar("select count(distinct updated_at) from members")).longValue());
    }

    @Test
    void namedUpdateColumnsGetUpdatedAtAddedButNeverCreatedAt() {
        members(FIRST).query().upsert(List.of(row("email", "a@x", "name", "A")), List.of("email"));

        members(SECOND).query().upsert(List.of(row("email", "a@x", "name", "Z")), List.of("email"), List.of("email"));

        Map<String, Object> a = database.rows("select * from members").get(0);
        assertEquals("A", a.get("name"));
        assertEquals("2026-11-02 10:15:30", a.get("created_at"));
        assertEquals("2026-11-03 08:00:00", a.get("updated_at"));
    }

    @Test
    void anEmptyUpdateLeavesAConflictingRowUntouched() {
        members(FIRST).query().upsert(List.of(row("email", "a@x", "name", "A")), List.of("email"));

        members(SECOND).query().upsert(List.of(row("email", "a@x", "name", "Z")), List.of("email"),
                Collections.emptyList());

        Map<String, Object> a = database.rows("select * from members").get(0);
        assertEquals("A", a.get("name"));
        assertEquals("2026-11-02 10:15:30", a.get("updated_at"));
    }

    @Test
    void uuidKeysAreGeneratedForRowsWithoutOneAndKeptForRowsWithOne() {
        Ticket.QUERY.on(db).query().upsert(List.of(row("code", "c1", "title", "T1"),
                row("code", "c2", "title", "T2")), List.of("code"));
        Ticket.QUERY.on(db).query().upsert(List.of(row("id", "fixed", "code", "c3", "title", "T3")), List.of("code"));
        String firstId = (String) database.scalar("select id from tickets where code = 'c1'");

        Ticket.QUERY.on(db).query().upsert(List.of(row("code", "c1", "title", "T1b")), List.of("code"), List.of("title"));

        assertEquals(36, firstId.length());
        assertNotEquals(firstId, database.scalar("select id from tickets where code = 'c2'"));
        assertEquals("fixed", database.scalar("select id from tickets where code = 'c3'"));
        assertEquals("T1b", database.scalar("select title from tickets where code = 'c1'"));
        assertEquals(3L, ((Number) database.scalar("select count(*) from tickets")).longValue());
    }

    @Test
    void aModelWithoutTimestampsWritesOnlyTheGivenColumns() {
        Tag.QUERY.on(db).query().upsert(List.of(row("label", "x", "note", "1")), List.of("label"));
        Tag.QUERY.on(db).query().upsert(List.of(row("label", "x", "note", "2")), List.of("label"));

        assertEquals(1L, ((Number) database.scalar("select count(*) from tags")).longValue());
        assertEquals("2", database.scalar("select note from tags"));
    }

    @Test
    void insertOrIgnoreFillsTimestampsAndSkipsDuplicates() {
        members(FIRST).query().insertOrIgnore(row("email", "a@x", "name", "A"));

        int added = members(SECOND).query().insertOrIgnore(List.of(row("email", "a@x", "name", "Z"),
                row("email", "b@x", "name", "B")));

        assertEquals(1, added);
        assertEquals("A", database.scalar("select name from members where email = 'a@x'"));
        assertEquals("2026-11-02 10:15:30", database.scalar("select created_at from members where email = 'a@x'"));
        assertEquals("2026-11-03 08:00:00", database.scalar("select created_at from members where email = 'b@x'"));
    }

    @Test
    void insertOrIgnoreGeneratesUniqueIds() {
        int added = Ticket.QUERY.on(db).query().insertOrIgnore(List.of(row("code", "c1", "title", "T1")));

        assertEquals(1, added);
        assertEquals(36, ((String) database.scalar("select id from tickets")).length());
    }

    @Test
    void emptyInputIsHandledByTheCoreRules() {
        assertEquals(0, members(FIRST).query().upsert(Collections.emptyList(), List.of("email")));
        assertEquals(0, members(FIRST).query().insertOrIgnore(Collections.<Map<String, Object>>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> members(FIRST).query().upsert(List.of(row("email", "a")), Collections.emptyList()));
        assertNull(database.scalar("select id from members"));
    }
}

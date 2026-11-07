package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.SQLiteModelTest.SingleConnection;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The {@code instant} cast against a real in-memory SQLite database, and its fail-fast check. */
class InstantCastTest {

    private static final Instant TEN_UTC = Instant.parse("2024-03-01T10:00:00Z");
    private static final Instant NANO_MOMENT = Instant.parse("2026-11-02T10:15:30.123456789Z");
    private static final Instant MICRO_MOMENT = Instant.parse("2026-11-02T10:15:30.123456Z");

    /** An event with one instant column on a model that keeps the default text timestamp format. */
    static final class Event extends Model {
        static final ModelQuery<Event> QUERY = ModelQuery.of(Event.class, Event::new);

        @Override
        protected String table() {
            return "events";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("at", Casts.instant());
            return casts;
        }
    }

    /** The same event declared with the spec string instead of the factory. */
    static final class SpecEvent extends Model {
        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("at", Casts.of("instant"));
            return casts;
        }
    }

    /** A text-format model that puts the instant cast on its created_at column. */
    static final class StampedEvent extends Model {
        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("at", Casts.instant());
            casts.put("created_at", Casts.instant());
            return casts;
        }
    }

    /** An event whose text column carries the instant cast, to read the text stored form. */
    static final class TextEvent extends Model {
        static final ModelQuery<TextEvent> QUERY = ModelQuery.of(TextEvent.class, TextEvent::new);

        @Override
        protected String table() {
            return "text_events";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("at", Casts.instant());
            return casts;
        }
    }

    /** A model with timestamps off that casts created_at as an instant, which is allowed. */
    static final class UnstampedEvent extends Model {
        static final ModelQuery<UnstampedEvent> QUERY = ModelQuery.of(UnstampedEvent.class, UnstampedEvent::new);

        @Override
        protected String table() {
            return "events";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("created_at", Casts.instant());
            return casts;
        }
    }

    private java.sql.Connection jdbc;
    private QueryFactory db;

    @BeforeEach
    void open() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        Connection connection = Connection.builder().name("sqlite").connectionProvider(new SingleConnection(jdbc))
                .build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("events", t -> {
            t.id();
            t.instant("at");
            t.instant("created_at").nullable();
        });
        SQLiteSchema.of(connection).create("text_events", t -> {
            t.id();
            t.string("at");
        });
    }

    @AfterEach
    void close() throws SQLException {
        jdbc.close();
    }

    @Test
    void writesInstantWhateverTheModelFormat() {
        Instant now = Instant.now();

        Event created = Event.QUERY.on(db).create(row("at", now));
        Event found = Event.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(now.truncatedTo(ChronoUnit.MICROS), found.getAttribute("at"));
    }

    @Test
    void truncatesToMicros() {
        Event event = new Event();

        event.setAttribute("at", NANO_MOMENT);

        assertEquals(MICRO_MOMENT, event.getAttribute("at"));
    }

    @Test
    void acceptsEveryMomentForm() {
        Event event = new Event();
        Object[] inputs = {
            TEN_UTC,
            OffsetDateTime.of(2024, 3, 1, 14, 0, 0, 0, ZoneOffset.ofHours(4)),
            ZonedDateTime.of(2024, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC),
            LocalDateTime.of(2024, 3, 1, 10, 0),
            Date.from(TEN_UTC),
            "2024-03-01 10:00:00",
            "2024-03-01T14:00:00+04:00",
        };
        for (Object input : inputs) {
            event.setAttribute("at", input);
            assertEquals(TEN_UTC, event.getAttributes().get("at"), input.getClass().getName());
            // A Date is a mutable value kept by reference, so getAttribute hands the caller's Date back.
            if (!(input instanceof Date)) {
                assertEquals(TEN_UTC, event.getAttribute("at"), input.getClass().getName());
            }
        }
        event.setAttribute("at", LocalDate.of(2024, 3, 1));
        assertEquals(LocalDate.of(2024, 3, 1).atStartOfDay().toInstant(ZoneOffset.UTC),
                event.getAttribute("at"));
    }

    @Test
    void refusesABareNumber() {
        Event event = new Event();

        assertThrows(InvalidCastException.class, () -> event.setAttribute("at", 1_700_000_000L));
        assertThrows(InvalidCastException.class, () -> event.setAttribute("at", 1_700_000_000));
    }

    @Test
    void readsEveryStoredForm() throws SQLException {
        long micros = TEN_UTC.getEpochSecond() * 1_000_000L;
        long textRow = insertRaw("text_events", "'2024-03-01 10:00:00'");
        long intRow = insertRaw("events", String.valueOf(micros));

        assertEquals(TEN_UTC, TextEvent.QUERY.on(db).findOrFail(textRow).getAttribute("at"));
        assertEquals(TEN_UTC, Event.QUERY.on(db).findOrFail(intRow).getAttribute("at"));
    }

    @Test
    void specNameResolves() {
        SpecEvent event = new SpecEvent();

        event.setAttribute("at", TEN_UTC);

        assertEquals(TEN_UTC, event.getAttribute("at"));
        assertEquals("instant", Casts.typeName(Casts.of("instant")));
    }

    @Test
    void sameInstantIsNotDirty() {
        Event created = Event.QUERY.on(db).create(row("at", TEN_UTC));
        Event found = Event.QUERY.on(db).findOrFail(created.getKey());

        found.setAttribute("at", OffsetDateTime.ofInstant(TEN_UTC, ZoneId.of("Asia/Dubai")));

        assertFalse(found.isDirty("at"));
    }

    @Test
    void timestampColumnWithInstantCastOnTextModelFailsFast() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StampedEvent().getAttribute("created_at"));

        assertTrue(failure.getMessage().contains(StampedEvent.class.getName()));
        assertTrue(failure.getMessage().contains("created_at"));
        assertTrue(failure.getMessage().contains("TimestampFormat.INSTANT"));
    }

    @Test
    void mergeCastsAlsoFailsFast() {
        Model plain = new Model() {
            @Override
            protected String table() {
                return "events";
            }
        };
        Map<String, Object> merged = Collections.<String, Object>singletonMap("created_at", Casts.instant());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> plain.mergeCasts(merged));

        assertTrue(failure.getMessage().contains("created_at"));
    }

    @Test
    void timestampsOffModelMayCastCreatedAtAsInstant() {
        UnstampedEvent event = new UnstampedEvent();

        event.setAttribute("created_at", TEN_UTC);

        assertEquals(TEN_UTC, event.getAttribute("created_at"));
        assertEquals(TEN_UTC, UnstampedEvent.QUERY.on(db).create(row("at", TEN_UTC, "created_at", TEN_UTC))
                .getAttribute("created_at"));
    }

    /** Inserts one row with the given SQL literal as its {@code at} value and returns its id. */
    private long insertRaw(String table, String literal) throws SQLException {
        try (Statement statement = jdbc.createStatement()) {
            statement.executeUpdate("insert into " + table + " (at) values (" + literal + ")");
            try (java.sql.ResultSet keys = statement.executeQuery("select max(id) from " + table)) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }
}

package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Model factories against a throwaway PostgreSQL; skipped when the server binaries are missing. */
class PostgresFactoryTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30Z");

    /** A UUID-keyed model with an array cast. */
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

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("tags", Casts.of("array"));
        }
    }

    /** A soft-deleting page. */
    static final class Page extends Model {
        static final ModelQuery<Page> QUERY = ModelQuery.of(Page.class, Page::new);

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private Connection connection;

    private static void createTables(Connection target) {
        target.statement("create table tickets (id varchar(36) primary key, code varchar(255) not null, "
                + "tags text, created_at timestamp(0), updated_at timestamp(0))");
        target.statement("create table pages (id bigserial primary key, title varchar(255) not null, "
                + "created_at timestamp(0), updated_at timestamp(0), deleted_at timestamp(0))");
    }

    @BeforeEach
    void open() {
        connection = PostgresServer.newDatabase();
        createTables(connection);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private Factory<Ticket> tickets(Connection target) {
        return Factory.of(Ticket.QUERY.on(PostgresQuery.of(target)).withClock(ModelClock.fixed(NOW)),
                () -> map("code", "c", "tags", List.of("a", "b")));
    }

    private static long count(Connection target, String table) {
        return ((Number) target.select("select count(*) as v from " + table).get(0).get("v")).longValue();
    }

    @Test
    void createStoresARowWithAUuidKey() {
        Ticket ticket = tickets(connection).create();

        assertNotNull(ticket.getKey());
        assertEquals(36, String.valueOf(ticket.getKey()).length());
        assertEquals(1L, count(connection, "tickets"));
    }

    @Test
    void anArrayCastReadsBackEqual() {
        Ticket created = tickets(connection).create();

        Ticket reloaded = Ticket.QUERY.on(PostgresQuery.of(connection)).findOrFail(created.getKey());

        assertEquals(List.of("a", "b"), reloaded.getAttribute("tags"));
    }

    @Test
    void insertWritesThreeRows() {
        tickets(connection).insert(3);

        assertEquals(3L, count(connection, "tickets"));
        assertEquals(3L, ((Number) connection.select("select count(distinct id) as v from tickets").get(0).get("v"))
                .longValue());
    }

    @Test
    void trashedStoresDeletedAt() {
        Factory.of(Page.QUERY.on(PostgresQuery.of(connection)).withClock(ModelClock.fixed(NOW)),
                () -> map("title", "p")).trashed().create();

        Object stored = connection.select("select cast(deleted_at as varchar) as v from pages").get(0).get("v");
        assertEquals(NOW.minus(Duration.ofDays(1)).toString().replace('T', ' ').replace("Z", ""), stored);
    }

    @Test
    void connectionSelectsAnotherDatabase() {
        Connection second = PostgresServer.newDatabase();
        createTables(second);

        tickets(connection).connection(second).create();

        assertEquals(1L, count(second, "tickets"));
        assertEquals(0L, count(connection, "tickets"));
    }
}

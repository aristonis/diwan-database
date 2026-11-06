package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.PostgresServer;
import org.aristonis.diwan.database.eloquent.TimestampFormat;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * belongsToMany writes executed on a throwaway PostgreSQL: the insert and delete shapes, the
 * transaction and savepoint behaviour that differs from SQLite, and the timestamp value bound.
 * Skipped when the server binaries are missing, and failing when {@code DIWAN_PG_REQUIRED} is set.
 * The models store timestamps as {@code Instant}, which the {@code timestamp} columns take.
 */
class PostgresBelongsToManyWritesTest {

    /** More ids than one insert holds: four columns per row against the 10,000 binding budget. */
    private static final int ROWS_PAST_ONE_STATEMENT = 2_501;

    private final QueryLog log = new QueryLog();
    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void open() {
        connection = PostgresServer.newDatabase();
        db = PostgresQuery.of(log.wrap(connection));
        PgUser.QUERY.on(db);
        PgRole.QUERY.on(db);
        connection.statement("create table users (id bigserial primary key, name varchar(255),"
                + " created_at timestamp, updated_at timestamp)");
        connection.statement("create table roles (id bigserial primary key, name varchar(255),"
                + " created_at timestamp, updated_at timestamp)");
        connection.statement("create table role_user (user_id bigint not null, role_id bigint not null,"
                + " status varchar(255), created_at timestamp, updated_at timestamp)");
        connection.statement("insert into users (id, name) values (1, 'User 1')");
        connection.statement("insert into roles (id, name) values (1, 'admin'), (2, 'editor'), (3, 'viewer')");
    }

    @Test
    void attachAndDetachShapes() {
        PgUser ann = user(db);
        log.clear();

        ann.roles().attach(Arrays.asList(1L, 2L), Collections.<String, Object>singletonMap("status", "active"));
        int deleted = ann.roles().wherePivot("status", "active").detach(Arrays.asList(1L, 2L));

        assertEquals(Arrays.asList(
                "insert into \"role_user\" (\"created_at\", \"role_id\", \"status\", \"updated_at\", \"user_id\")"
                        + " values (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)",
                "delete from \"role_user\" where (\"role_user\".\"status\" = ?) and \"role_user\".\"user_id\" = ?"
                        + " and \"role_user\".\"role_id\" in (?, ?)"), log.writes());
        assertEquals(Arrays.asList(10, 4), log.writeBindingCounts());
        assertEquals(2, deleted);
        assertEquals(0L, count("select count(*) as v from role_user"));
    }

    @Test
    void createOrFirstSurvivesUniqueViolation() {
        connection.statement("create unique index roles_name_unique on roles (name)");
        Map<String, Object> admin = Collections.<String, Object>singletonMap("name", "admin");

        long linked = connection.transaction(c -> {
            PgUser ann = user(PostgresQuery.of(c));
            PgRole found = ann.roles().createOrFirst(admin);
            assertEquals(1L, ((Number) found.getKey()).longValue());
            return ((Number) c.selectOne("select count(*) as v from role_user where user_id = 1 and role_id = 1")
                    .orElseThrow(IllegalStateException::new).get("v")).longValue();
        });

        assertEquals(1L, linked);
        assertEquals(3L, count("select count(*) as v from roles"));
    }

    @Test
    void syncWithRootBoundParentInStrictModeSucceeds() {
        PgUser ann = user(db);
        ann.roles().attach(Arrays.asList(1L, 2L));

        SyncResult result = ann.roles().sync(Arrays.asList(2L, 3L));

        assertEquals(Collections.singletonList(3L), result.getAttached());
        assertEquals(Collections.singletonList(1L), result.getDetached());
        assertEquals(2L, count("select count(*) as v from role_user where user_id = 1"));
        assertEquals(1L, count("select count(*) as v from role_user where user_id = 1 and role_id = 3"));
    }

    @Test
    void attachBindsTimestampAsInstantValue() {
        PgUser ann = user(db);

        assertInstanceOf(Instant.class, ann.freshTimestampValue());
        ann.roles().attach(1L);

        Map<String, Object> row = connection.selectOne("select created_at, updated_at from role_user")
                .orElseThrow(IllegalStateException::new);
        assertInstanceOf(LocalDateTime.class, row.get("created_at"));
        assertEquals(row.get("created_at"), row.get("updated_at"));
    }

    @Test
    void multiChunkAttachRollsBackOnFailure() {
        connection.statement("create unique index role_user_link on role_user (user_id, role_id)");
        connection.statement("insert into role_user (user_id, role_id) values (1, " + ROWS_PAST_ONE_STATEMENT + ")");
        PgUser ann = user(db);

        assertThrows(UniqueConstraintViolationException.class, () -> ann.roles().attach(roleIds()));

        assertEquals(1L, count("select count(*) as v from role_user"));
    }

    @Test
    void attachInsideCallerTransactionWithTransactionBoundParentUsesSavepoint() {
        connection.statement("create unique index role_user_link on role_user (user_id, role_id)");
        connection.statement("insert into role_user (user_id, role_id) values (1, " + ROWS_PAST_ONE_STATEMENT + ")");

        connection.transaction(c -> {
            PgUser ann = user(PostgresQuery.of(c));
            assertThrows(UniqueConstraintViolationException.class, () -> ann.roles().attach(roleIds()));
            ann.roles().attach(2L);
            return null;
        });

        assertEquals(2L, count("select count(*) as v from role_user"));
        assertEquals(1L, count("select count(*) as v from role_user where user_id = 1 and role_id = 2"));
    }

    @Test
    void attachInsideCallerTransactionWithRootBoundParentIsRefused() {
        PgUser ann = user(db);

        assertThrows(IllegalStateException.class, () -> connection.transaction(c -> {
            ann.roles().attach(1L);
            return null;
        }));

        assertEquals(0L, count("select count(*) as v from role_user"));
    }

    // ---- helpers

    private static PgUser user(QueryFactory factory) {
        return PgUser.QUERY.on(factory).findOrFail(1);
    }

    private static List<Object> roleIds() {
        List<Object> ids = new ArrayList<>();
        for (long id = 1; id <= ROWS_PAST_ONE_STATEMENT; id++) {
            ids.add(id);
        }
        return ids;
    }

    private long count(String sql) {
        Map<String, Object> row = new LinkedHashMap<>(connection.selectOne(sql).orElseThrow(IllegalStateException::new));
        return ((Number) row.get("v")).longValue();
    }

    /** A user that belongs to many roles through the {@code role_user} table. */
    static final class PgUser extends Model {
        static final ModelQuery<PgUser> QUERY = ModelQuery.of(PgUser.class, PgUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected TimestampFormat timestampFormat() {
            return TimestampFormat.INSTANT;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(PgUser.class).add("roles", PgUser::roles);
        }

        /**
         * Belongs to many roles with timestamps on the join rows.
         *
         * @return the relation, never {@code null}
         */
        public BelongsToMany<PgRole> roles() {
            return belongsToMany(PgRole.class, "role_user", "user_id", "role_id").withTimestamps();
        }
    }

    /** A role whose name can be mass assigned. */
    static final class PgRole extends Model {
        static final ModelQuery<PgRole> QUERY = ModelQuery.of(PgRole.class, PgRole::new);

        @Override
        protected String table() {
            return "roles";
        }

        @Override
        protected List<String> fillable() {
            return Collections.singletonList("name");
        }

        @Override
        protected TimestampFormat timestampFormat() {
            return TimestampFormat.INSTANT;
        }
    }
}

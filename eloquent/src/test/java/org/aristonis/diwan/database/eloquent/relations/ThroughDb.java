package org.aristonis.diwan.database.eloquent.relations;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.PostgresServer;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;

/**
 * A database with the through-relation tables and a log of every select the factory runs. The
 * models use {@link #db}; {@link #real} is the unlogged connection underneath it.
 *
 * <p>{@link #open()} gives an in-memory SQLite database, and {@link #openPostgres()} gives a new
 * PostgreSQL database on the shared test server. Both start with empty tables; call {@link #seed()}
 * for the fixture rows.
 */
final class ThroughDb implements AutoCloseable {

    private static final String SQLITE_KEY = "integer primary key autoincrement";
    private static final String POSTGRES_KEY = "bigserial primary key";
    private static final String SQLITE_URL = "jdbc:sqlite::memory:";

    /** The logged factory the models are bound to. */
    final QueryFactory db;

    /** The unlogged connection, for tests that need the raw database. */
    final Connection real;

    /** The select log of {@link #db}. */
    final QueryLog log;

    private final java.sql.Connection jdbc;

    private ThroughDb(Connection real, QueryLog log, QueryFactory db, java.sql.Connection jdbc) {
        this.real = real;
        this.log = log;
        this.db = db;
        this.jdbc = jdbc;
        ThroughModels.register();
    }

    /**
     * Opens a new in-memory SQLite database with the through-relation tables, all empty.
     *
     * @return a new database instance, never {@code null}
     * @throws SQLException if the database cannot be opened or the tables cannot be created
     */
    static ThroughDb open() throws SQLException {
        java.sql.Connection jdbc = DriverManager.getConnection(SQLITE_URL);
        try {
            Connection real = Connection.builder().name("sqlite").connectionProvider(new Single(jdbc)).build();
            QueryLog log = new QueryLog();
            ThroughDb created = new ThroughDb(real, log, SQLiteQuery.of(log.wrap(real)), jdbc);
            created.createTables(SQLITE_KEY);
            return created;
        } catch (RuntimeException e) {
            jdbc.close();
            throw e;
        }
    }

    /**
     * Opens a new, empty PostgreSQL database with the through-relation tables, or skips the test when
     * PostgreSQL is not installed.
     *
     * @return a new database instance, never {@code null}
     */
    static ThroughDb openPostgres() {
        Connection real = PostgresServer.newDatabase();
        QueryLog log = new QueryLog();
        ThroughDb created = new ThroughDb(real, log, PostgresQuery.of(log.wrap(real)), null);
        created.createTables(POSTGRES_KEY);
        return created;
    }

    /**
     * Inserts the fixture rows and clears the log, so the test starts with no recorded selects or
     * writes.
     *
     * <p>Countries: 1 United States, 2 France, 3 Japan (no users).
     * Users: 1 Ann and 2 Ben in country 1, 3 Cleo in country 2.
     * Posts: 1 and 2 by user 1, 3 by user 2, 4 by user 3, 5 by person 2, 6 by person 3 (no user).
     * Mechanics: 1 Mo with car 1, 2 Pat with no car, so no owner.
     * Cars: 1 Civic of mechanic 1. Owners: 1 Olga of car 1.
     * Teams: 1 Engine led by staff 1, 2 Body led by staff 3.
     * Staff: 1 Mae and 2 Sam in team 1, 3 Lee in team 2, 4 Kim in no team.
     * People: 1 Grace with no manager, 2 Alan and 3 Ada managed by person 1.
     *
     * <p>Ids are explicit, so the tables hold ids 1 to the seeded count. PostgreSQL sequences are not
     * advanced by explicit ids: a test that inserts a row without an id on PostgreSQL must pass its own id.
     */
    void seed() {
        insert("countries", "id", 1, "name", "United States");
        insert("countries", "id", 2, "name", "France");
        insert("countries", "id", 3, "name", "Japan");

        insert("users", "id", 1, "country_id", 1, "name", "Ann");
        insert("users", "id", 2, "country_id", 1, "name", "Ben");
        insert("users", "id", 3, "country_id", 2, "name", "Cleo");

        insert("posts", "id", 1, "user_id", 1, "person_id", null, "title", "Post 1");
        insert("posts", "id", 2, "user_id", 1, "person_id", null, "title", "Post 2");
        insert("posts", "id", 3, "user_id", 2, "person_id", null, "title", "Post 3");
        insert("posts", "id", 4, "user_id", 3, "person_id", null, "title", "Post 4");
        insert("posts", "id", 5, "user_id", null, "person_id", 2, "title", "Post 5");
        insert("posts", "id", 6, "user_id", null, "person_id", 3, "title", "Post 6");

        insert("mechanics", "id", 1, "name", "Mo");
        insert("mechanics", "id", 2, "name", "Pat");
        insert("cars", "id", 1, "mechanic_id", 1, "model", "Civic");
        insert("owners", "id", 1, "car_id", 1, "name", "Olga");

        insert("teams", "id", 1, "lead_id", 1, "name", "Engine");
        insert("teams", "id", 2, "lead_id", 3, "name", "Body");
        insert("staff", "id", 1, "team_id", 1, "name", "Mae");
        insert("staff", "id", 2, "team_id", 1, "name", "Sam");
        insert("staff", "id", 3, "team_id", 2, "name", "Lee");
        insert("staff", "id", 4, "team_id", null, "name", "Kim");

        insert("people", "id", 1, "manager_id", null, "name", "Grace");
        insert("people", "id", 2, "manager_id", 1, "name", "Alan");
        insert("people", "id", 3, "manager_id", 1, "name", "Ada");

        log.clear();
    }

    /**
     * Closes the SQLite connection. A PostgreSQL database stays on the shared server until the JVM
     * exits, so there is nothing to release for it.
     *
     * @throws SQLException if the SQLite connection cannot be closed
     */
    @Override
    public void close() throws SQLException {
        if (jdbc != null) {
            jdbc.close();
        }
    }

    private void createTables(String key) {
        for (String statement : schema(key)) {
            real.statement(statement);
        }
    }

    private static List<String> schema(String key) {
        return Arrays.asList(
                "create table countries (id " + key + ", name varchar(255) not null)",
                "create table users (id " + key + ", country_id bigint, name varchar(255) not null)",
                "create table posts (id " + key + ", user_id bigint, person_id bigint, title varchar(255) not null)",
                "create table mechanics (id " + key + ", name varchar(255) not null)",
                "create table cars (id " + key + ", mechanic_id bigint not null, model varchar(255) not null)",
                "create table owners (id " + key + ", car_id bigint not null, name varchar(255) not null)",
                "create table teams (id " + key + ", lead_id bigint, name varchar(255) not null)",
                "create table staff (id " + key + ", team_id bigint, name varchar(255) not null)",
                "create table people (id " + key + ", manager_id bigint, name varchar(255) not null)"
        );
    }

    /**
     * Inserts one row through the logged factory. Writes are kept apart from the select log.
     *
     * @param table the table name, never {@code null}
     * @param keyValues the column names and values, alternating, with an even count
     * @throws IllegalArgumentException if the column and value count is odd
     */
    private void insert(String table, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("column names and values must come in pairs");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        db.table(table).insert(row);
    }

    /**
     * Hands out the one JDBC connection; {@link ThroughDb#close()} closes it.
     */
    private static final class Single implements JdbcConnectionProvider {
        private final java.sql.Connection connection;

        Single(java.sql.Connection connection) {
            this.connection = connection;
        }

        @Override
        public java.sql.Connection acquire() {
            return connection;
        }

        @Override
        public void release(java.sql.Connection released) {
            // Closed by ThroughDb.close().
        }
    }
}

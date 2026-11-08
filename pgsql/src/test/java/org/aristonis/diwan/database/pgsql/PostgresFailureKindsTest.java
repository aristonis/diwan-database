package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.ForeignKeyViolationException;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.junit.jupiter.api.Test;

class PostgresFailureKindsTest {

    private static DriverException driverFailure(QueryException error) {
        return (DriverException) error.getCause();
    }

    @Test
    void aForeignKeyViolationIsItsOwnException() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table parents (id int primary key)");
        db.statement("create table kids (parent_id int references parents (id))");

        ForeignKeyViolationException error = assertThrows(ForeignKeyViolationException.class,
                () -> db.insert("insert into kids values (?)", 99));
        assertTrue(driverFailure(error).isForeignKeyViolation());
    }

    @Test
    void aCancelledStatementIsMarkedCancelled() {
        Connection db = PostgresServer.newDatabase();

        QueryException error = assertThrows(QueryException.class, () -> db.session(s -> {
            s.statement("set statement_timeout = 50");
            return s.select("select pg_sleep(2)");
        }));
        assertTrue(driverFailure(error).kinds().contains(DriverFailureKind.CANCELLED));
    }

    @Test
    void aLockTimeoutIsAConcurrencyFailure() throws Exception {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table t (id int primary key)");
        db.insert("insert into t values (1)");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> db.transaction(tx -> {
                tx.select("select * from t where id = 1 for update");
                locked.countDown();
                try {
                    done.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            QueryException error = assertThrows(QueryException.class, () -> db.session(s -> {
                s.statement("set lock_timeout = 50");
                return s.select("select * from t where id = 1 for update");
            }));

            done.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertTrue(driverFailure(error).isConcurrencyFailure());
        } finally {
            done.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aConnectionLostDuringCommitMarksTheOutcomeUnknown() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table t (n int)");
        String database = String.valueOf(db.scalar("select current_database()").get());
        Connection other = Connection.builder().name("other").dataSource(PostgresServer.dataSource(database)).build();

        QueryException error = assertThrows(QueryException.class, () -> db.transaction(tx -> {
            tx.insert("insert into t values (1)");
            Object pid = tx.scalar("select pg_backend_pid()").get();
            other.select("select pg_terminate_backend(?::int)", pid);
            return null;
        }));

        assertTrue(driverFailure(error).kinds().contains(DriverFailureKind.COMMIT_OUTCOME_UNKNOWN),
                driverFailure(error).kinds().toString());
    }

    @Test
    void aTerminatedSessionIsConnectionLost() {
        Connection db = PostgresServer.newDatabase();

        QueryException error = assertThrows(QueryException.class,
                () -> db.select("select pg_terminate_backend(pg_backend_pid())"));
        assertTrue(driverFailure(error).kinds().contains(DriverFailureKind.CONNECTION_LOST),
                driverFailure(error).kinds().toString());
    }

    @Test
    void messageNamesUniqueViolation() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table u (id int primary key)");
        db.insert("insert into u values (?)", 1);

        QueryException error = assertThrows(QueryException.class, () -> db.insert("insert into u values (?)", 1));

        assertEquals("Database failure (SQL state 23505, vendor code 0): unique violation"
                + " [DWN-CST-0001 unique violation: u_pkey]",
                driverFailure(error).getMessage());
    }
}

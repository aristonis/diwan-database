package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PretendTest {

    private final FakeDriver driver = new FakeDriver();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    @Test
    void recordsEveryStatementWithItsBindingsWithoutTouchingTheDatabase() {
        List<LoggedQuery> queries = connection().pretend(p -> {
            p.statement("create table t (id integer)");
            p.insert("insert into t values (?)", 1);
            p.update("update t set id = ?", 2);
            p.select("select * from t where id = ?", 2);
            p.unprepared("drop table t");
        });

        assertEquals(Arrays.asList(
                new LoggedQuery("create table t (id integer)", Arrays.asList()),
                new LoggedQuery("insert into t values (?)", Arrays.<Object>asList(1)),
                new LoggedQuery("update t set id = ?", Arrays.<Object>asList(2)),
                new LoggedQuery("select * from t where id = ?", Arrays.<Object>asList(2)),
                new LoggedQuery("drop table t", Arrays.asList())), queries);
        assertEquals(0, driver.sessionsOpened());
    }

    @Test
    void answersWithNeutralResults() {
        connection().pretend(p -> {
            assertTrue(p.select("select * from t").isEmpty());
            assertFalse(p.selectOne("select * from t").isPresent());
            assertFalse(p.scalar("select count(*) from t").isPresent());
            assertEquals(0, p.affectingStatement("delete from t"));
            assertTrue(p.statement("create table t (id integer)"));
            assertTrue(p.insert("insert into t values (1)"));
            assertTrue(p.unprepared("vacuum"));
            assertEquals(0, p.transactionLevel());
        });
    }

    @Test
    void runsATransactionInsidePretendOnTheSameRecorder() {
        List<LoggedQuery> queries = connection().pretend(p ->
                p.transaction(tx -> tx.insert("insert into t values (1)")));

        assertEquals(1, queries.size());
        assertEquals(0, driver.sessionsOpened());
    }

    @Test
    void returnsAnUnmodifiableList() {
        List<LoggedQuery> queries = connection().pretend(p -> p.statement("select 1"));

        assertThrows(UnsupportedOperationException.class, () -> queries.clear());
    }

    @Test
    void stillRefusesUnsupportedBindings() {
        assertThrows(IllegalArgumentException.class, () ->
                connection().pretend(p -> p.insert("insert into t values (?)", new Object())));
    }

    @Test
    void refusesANullCallback() {
        assertThrows(NullPointerException.class, () -> connection().pretend(null));
    }
}

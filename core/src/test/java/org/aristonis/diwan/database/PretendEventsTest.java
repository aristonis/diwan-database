package org.aristonis.diwan.database;

import org.aristonis.diwan.database.event.QueryExecuted;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pretend mode reports the statements it would have run, with no time spent. */
class PretendEventsTest {

    private final FakeDriver driver = new FakeDriver();
    private final RecordingListener listener = new RecordingListener();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).listener(listener).build();
    }

    @Test
    void firesAnEventPerPretendedStatementWithZeroTime() {
        connection().pretend(p -> {
            p.insert("insert into t values (?)", 1);
            p.select("select * from t");
        });

        List<QueryExecuted> events = listener.executed();
        assertEquals(2, events.size());
        assertEquals(Duration.ZERO, events.get(0).time());
        assertEquals(Duration.ZERO, events.get(1).time());
    }

    @Test
    void carriesKindSqlAndBindingsOfEachPretendedStatement() {
        connection().pretend(p -> {
            p.update("update t set a = ?", 5);
            p.delete("delete from t");
        });

        List<QueryExecuted> events = listener.executed();
        assertEquals(QueryKind.UPDATE, events.get(0).kind());
        assertEquals(Arrays.<Object>asList(5), events.get(0).bindings());
        assertEquals(QueryKind.DELETE, events.get(1).kind());
        assertEquals("delete from t", events.get(1).sql());
        assertEquals("main", events.get(0).connectionName());
    }

    @Test
    void neverOpensASessionWhileFiringTheEvents() {
        connection().pretend(p -> p.statement("create table t (a integer)"));

        assertEquals(0, driver.sessionsOpened());
    }

    @Test
    void stillReturnsTheRecordedStatements() {
        List<LoggedQuery> queries = connection().pretend(p -> p.statement("create table t (a integer)"));

        assertEquals(Arrays.asList(new LoggedQuery("create table t (a integer)", Arrays.asList())), queries);
    }

    @Test
    void worksWithoutAnyListener() {
        Connection db = Connection.builder().name("main").driver(driver).build();

        List<LoggedQuery> queries = db.pretend(p -> p.select("select 1"));

        assertEquals(1, queries.size());
    }
}

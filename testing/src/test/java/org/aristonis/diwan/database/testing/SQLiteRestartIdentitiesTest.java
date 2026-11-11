package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The {@code restartingIdentities()} option on SQLite: a rollback already restores the counters, so
 * the ids restart and no restart statement is sent. Also the name checks of
 * {@link QueryFactory#restartIdentities(List)}.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class SQLiteRestartIdentitiesTest {

    private static final Connection CONNECTION = Fixtures.sqlite("sqlite-restart");
    private static final Pattern RESTART_SQL = Pattern.compile("(?i)setval|restart|alter\\s+sequence");

    static {
        CONNECTION.events().enableQueryLog();
    }

    @RegisterExtension
    final RefreshDatabase db = RefreshDatabase.rollback(() -> SQLiteQuery.of(CONNECTION), SQLiteSchema::of,
            Fixtures.base()).restartingIdentities();

    @Test
    void a1IdsStartAtOne(QueryFactory query) {
        assertEquals(Arrays.asList(1L, 2L, 3L), insertThreeUsers(query));
    }

    @Test
    void a2IdsStartAtOneAgainAndNoRestartStatementWasSent(QueryFactory query) {
        assertEquals(Arrays.asList(1L, 2L, 3L), insertThreeUsers(query));
        assertEquals(Collections.emptyList(), restartStatements());
    }

    @Test
    void restartingTablesSendsNoStatementOnSqlite(QueryFactory query) {
        CONNECTION.events().flushQueryLog();

        query.restartIdentities(Arrays.asList("users", "posts"));

        assertEquals(Collections.emptyList(), CONNECTION.events().getQueryLog());
    }

    @Test
    void anEmptyListSendsNoStatement(QueryFactory query) {
        CONNECTION.events().flushQueryLog();

        query.restartIdentities(Collections.emptyList());

        assertEquals(Collections.emptyList(), CONNECTION.events().getQueryLog());
    }

    @Test
    void aNullListIsRefused(QueryFactory query) {
        assertThrows(NullPointerException.class, () -> query.restartIdentities(null));
    }

    @Test
    void aBlankNameIsRefused(QueryFactory query) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> query.restartIdentities(Arrays.asList("users", "  ")));

        assertTrue(error.getMessage().contains("blank"), error.getMessage());
    }

    @Test
    void aNullNameIsRefused(QueryFactory query) {
        assertThrows(IllegalArgumentException.class, () -> query.restartIdentities(Arrays.asList("users", null)));
    }

    private static List<Long> insertThreeUsers(QueryFactory query) {
        for (String name : Arrays.asList("ann", "bob", "cy")) {
            query.table("users").insert(Map.of("name", name));
        }
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : CONNECTION.select("select id from users order by id")) {
            ids.add(((Number) row.get("id")).longValue());
        }
        return ids;
    }

    private static List<String> restartStatements() {
        List<String> found = new ArrayList<>();
        for (LoggedQuery logged : CONNECTION.events().getQueryLog()) {
            if (RESTART_SQL.matcher(logged.sql()).find()) {
                found.add(logged.sql());
            }
        }
        return found;
    }
}

package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** An unknown operator on a date-based where matches nothing and cannot widen the query. */
class SQLiteDateOperatorTest {

    private static final String INJECTION = "? OR 1=1";

    @TempDir
    Path folder;

    private QueryFactory db;

    @BeforeEach
    void seed() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("dates.db"));
        Connection connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("posts", t -> {
            t.id();
            t.string("created_at");
        });
        db.table("posts").insert(List.of(Map.of("created_at", "2018-01-02 10:00:00"),
                Map.of("created_at", "2018-01-02 11:00:00")));
    }

    @Test
    void whereDateWithAnUnknownOperatorMatchesNoRow() {
        assertEquals(0L, db.table("posts").whereDate("created_at", INJECTION, "2018-01-02").count());
    }

    @Test
    void orWhereDateWithAnUnknownOperatorLeavesOnlyTheFirstCondition() {
        assertEquals(1L, db.table("posts").where("id", 1).orWhereDate("created_at", INJECTION, "2018-01-02").count());
    }

    @Test
    void whereTimeAndWhereYearWithAnUnknownOperatorMatchNoRow() {
        assertEquals(0L, db.table("posts").whereTime("created_at", INJECTION, "10:00:00").count());
        assertEquals(0L, db.table("posts").whereYear("created_at", INJECTION, 2018).count());
        assertEquals(1L, db.table("posts").where("id", 2).orWhereTime("created_at", INJECTION, "10:00:00").count());
        assertEquals(1L, db.table("posts").where("id", 2).orWhereYear("created_at", INJECTION, 2018).count());
    }

    @Test
    void whereDayAndWhereMonthWithAnUnknownOperatorAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> db.table("posts").whereDay("created_at", INJECTION, 2));
        assertThrows(IllegalArgumentException.class, () -> db.table("posts").whereMonth("created_at", INJECTION, 1));
    }
}

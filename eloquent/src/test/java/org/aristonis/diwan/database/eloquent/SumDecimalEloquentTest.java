package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code sumDecimal} on the Eloquent builder over a real SQLite database. */
class SumDecimalEloquentTest {

    /** A priced line. */
    static final class Line extends Model {
        static final ModelQuery<Line> QUERY = ModelQuery.of(Line.class, Line::new);

        @Override
        protected String table() {
            return "lines";
        }

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

    @BeforeEach
    void open() throws SQLException {
        database = SqliteModelDb.open();
        database.sql("create table lines (id integer primary key, price numeric, kind text)");
        database.sql("insert into lines (price, kind) values (0.1, 'a'), (0.2, 'a'), (5, 'b')");
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    @Test
    void eloquent() {
        BigDecimal sum = Line.QUERY.on(database.db).where("kind", "a").sumDecimal("price", 2);

        assertEquals(new BigDecimal("0.30"), sum);
    }

    @Test
    void eloquentWithRoundingMode() {
        BigDecimal sum = Line.QUERY.on(database.db).where("kind", "a").sumDecimal("price", 0, RoundingMode.UP);

        assertEquals(new BigDecimal("1"), sum);
    }

    @Test
    void eloquentNegativeScale() {
        assertThrows(IllegalArgumentException.class, () -> Line.QUERY.on(database.db).query().sumDecimal("price", -1));
    }
}

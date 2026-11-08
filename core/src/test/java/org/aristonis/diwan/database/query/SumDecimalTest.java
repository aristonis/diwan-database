package org.aristonis.diwan.database.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import org.aristonis.diwan.database.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** {@code sumDecimal} against a real SQLite file, whose sums are doubles. */
class SumDecimalTest {

    @TempDir
    Path folder;

    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void open() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("sum.db"));
        connection = Connection.builder().name("main").dataSource(dataSource).build();
        db = QueryFactory.of(connection, new TestQueries.TestGrammar(""), new Processor());
        connection.statement("create table items (id integer primary key, price numeric, qty integer, tag text)");
    }

    @Test
    void roundsDrift() {
        connection.statement("insert into items (price, qty) values (0.1, 1), (0.2, 2)");

        BigDecimal sum = db.table("items").sumDecimal("price", 2);

        assertEquals(new BigDecimal("0.30"), sum);
        assertEquals(2, sum.scale());
    }

    @Test
    void emptyIsZero() {
        BigDecimal sum = db.table("items").sumDecimal("price", 2);

        assertEquals(new BigDecimal("0.00"), sum);
        assertEquals(2, sum.scale());
    }

    @Test
    void negativeScale() {
        assertThrows(IllegalArgumentException.class, () -> db.table("items").sumDecimal("price", -1));
    }

    @Test
    void nullArgumentsAreRefused() {
        assertThrows(NullPointerException.class, () -> db.table("items").sumDecimal(null, 2));
        assertThrows(NullPointerException.class, () -> db.table("items").sumDecimal("price", 2, null));
    }

    @Test
    void integerColumnSumsExactly() {
        connection.statement("insert into items (price, qty) values (1, 5), (2, 7)");

        assertEquals(new BigDecimal("12.000"), db.table("items").sumDecimal("qty", 3));
    }

    @Test
    void roundingModeApplies() {
        connection.statement("insert into items (price, qty) values (0.125, 1)");

        assertEquals(new BigDecimal("0.12"), db.table("items").sumDecimal("price", 2, RoundingMode.DOWN));
        assertEquals(new BigDecimal("0.13"), db.table("items").sumDecimal("price", 2));
    }

    @Test
    void whereFiltersTheSum() {
        connection.statement("insert into items (price, qty, tag) values (0.1, 1, 'a'), (0.2, 1, 'b'), (0.7, 1, 'a')");

        assertEquals(new BigDecimal("0.80"), db.table("items").where("tag", "a").sumDecimal("price", 2));
    }

    @Test
    void textSumIsParsed() {
        connection.statement("insert into items (price, qty, tag) values (1, 1, '2.5')");

        assertEquals(new BigDecimal("2.50"), db.table("items").sumDecimal("tag", 2));
    }
}

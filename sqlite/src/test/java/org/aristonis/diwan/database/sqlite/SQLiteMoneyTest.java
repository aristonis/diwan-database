package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.file.Path;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.schema.Money;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteMoneyTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createLedger() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("ledger.db"));
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        schema.create("lines", t -> {
            t.id();
            t.money("cents", 2);
            t.money("debit", 6).asDecimal(20).unsigned();
            t.money("balance", 6).asDecimal(20);
        });
    }

    private long sumCents() {
        return ((Number) db.scalar("select sum(cents) from lines").get()).longValue();
    }

    @Test
    void minorUnitsSumExactlyInSql() {
        for (int i = 0; i < 1000; i++) {
            db.insert("insert into lines (cents, debit, balance) values (?, ?, ?)",
                    Money.toMinor(new BigDecimal("0.10"), 2), Money.toText(new BigDecimal("0.1"), 6), "0.000000");
        }

        assertEquals(10000L, sumCents());
        assertEquals("integer", db.scalar("select typeof(cents) from lines limit 1").get());
    }

    @Test
    void decimalColumnsKeepTheExactText() {
        db.insert("insert into lines (cents, debit, balance) values (?, ?, ?)", 1,
                Money.toText(new BigDecimal("1E+3"), 6), Money.toText(new BigDecimal("-12.5"), 6));

        assertEquals("1000.000000", db.scalar("select debit from lines").get());
        assertEquals(new BigDecimal("-12.500000"), Money.fromText((String) db.scalar("select balance from lines").get(), 6));
    }

    @Test
    void theCheckRefusesTextThatIsNotCanonical() {
        for (String bad : new String[] {"1000.00", "01.000000", "1e3", "-1.000000", "1.0000000", "abc"}) {
            assertThrows(QueryException.class, () -> db.insert(
                    "insert into lines (cents, debit, balance) values (1, ?, '0.000000')", bad), bad);
        }
        assertThrows(QueryException.class, () -> db.insert(
                "insert into lines (cents, debit, balance) values (1, '1.000000', ?)", "-0.000000"));
        db.insert("insert into lines (cents, debit, balance) values (1, '1.000000', '-1.000000')");
    }

    @Test
    void aNullableDecimalMoneyColumnTakesNull() {
        schema.create("prices", t -> t.money("price", 2).asDecimal(12).nullable());

        db.insert("insert into prices (price) values (?)", (Object) null);

        assertEquals(1L, ((Number) db.scalar("select count(*) from prices where price is null").get()).longValue());
    }

    @Test
    void scaleZeroAndUnsignedShapesAreEnforced() {
        schema.create("units", t -> {
            t.money("whole", 0).asDecimal(5).unsigned();
        });
        db.insert("insert into units (whole) values ('12345')");
        for (String bad : new String[] {"-1", "1.0", "123456", "012"}) {
            assertThrows(QueryException.class, () -> db.insert("insert into units (whole) values (?)", bad), bad);
        }
    }

    @Test
    void theColumnsSurviveATableRebuild() {
        schema.table("lines", t -> t.money("cents", 2).change());

        assertThrows(QueryException.class, () -> db.insert(
                "insert into lines (cents, debit, balance) values (1, '1e3', '0.000000')"));
    }
}

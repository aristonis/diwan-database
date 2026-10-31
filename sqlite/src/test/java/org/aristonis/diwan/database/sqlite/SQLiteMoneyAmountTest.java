package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.values.Instants;
import org.aristonis.diwan.database.values.MoneyAmount;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteMoneyAmountTest {

    @TempDir
    Path folder;

    @Test
    void amountsAndInstantsRoundTripAndSort() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("m.db"));
        Connection db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        Schema schema = SQLiteSchema.of(db);
        schema.create("lines", t -> {
            t.id();
            t.money("cents", 2);
            t.money("exact", 2).asDecimal(12);
            t.instant("at");
        });
        MoneyAmount price = MoneyAmount.of(new BigDecimal("10.1"), 2);
        Instant early = Instant.parse("2026-01-01T00:00:00Z");
        Instant late = Instant.parse("2026-06-01T00:00:00.000001Z");
        db.insert("insert into lines (cents, exact, at) values (?, ?, ?)", price.toMinor(), price.toText(),
                Instants.toMicros(late));
        db.insert("insert into lines (cents, exact, at) values (?, ?, ?)", 5L, "0.05", Instants.toMicros(early));

        Map<String, Object> first = db.select("select * from lines order by at").get(0);
        Map<String, Object> second = db.select("select * from lines order by at").get(1);

        assertEquals(early, Instants.read(first.get("at")));
        assertEquals(late, Instants.read(second.get("at")));
        assertEquals(price, MoneyAmount.read(second.get("cents"), 2));
        assertEquals(price, MoneyAmount.read(second.get("exact"), 2));
        assertEquals("integer", db.scalar("select typeof(at) from lines limit 1").get());
        org.junit.jupiter.api.Assertions.assertThrows(org.aristonis.diwan.database.QueryException.class,
                () -> db.insert("insert into lines (cents, exact, at) values (1, '0.01', ?)", Instants.now()));
        Instants.toMicros(Instants.now());
    }
}

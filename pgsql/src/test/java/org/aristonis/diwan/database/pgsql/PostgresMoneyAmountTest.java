package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.values.Instants;
import org.aristonis.diwan.database.values.MoneyAmount;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Test;

class PostgresMoneyAmountTest {

    @Test
    void amountsAndInstantsReadTheSameAsOnSqlite() {
        Connection db = PostgresServer.newDatabase();
        Schema schema = PostgresSchema.of(db);
        schema.create("lines", t -> {
            t.money("cents", 2);
            t.money("exact", 2).asDecimal(12);
            t.instant("at");
        });
        MoneyAmount price = MoneyAmount.of(new BigDecimal("10.1"), 2);
        Instant at = Instant.parse("2026-06-01T10:00:00.123456Z");

        db.insert("insert into lines values (?, ?, ?)", price.toMinor(), price.amount(), at);

        Map<String, Object> row = db.select("select * from lines").get(0);
        assertEquals(price, MoneyAmount.read(row.get("cents"), 2));
        assertEquals(price, MoneyAmount.read(row.get("exact"), 2));
        assertEquals(at, Instants.read(row.get("at")));
    }
}

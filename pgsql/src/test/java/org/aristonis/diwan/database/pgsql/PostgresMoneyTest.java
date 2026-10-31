package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Test;

class PostgresMoneyTest {

    @Test
    void minorUnitsAreBigintAndDecimalsAreNumeric() {
        Connection db = PostgresServer.newDatabase();
        Schema schema = PostgresSchema.of(db);
        schema.create("lines", t -> {
            t.money("cents", 2).unsigned();
            t.money("debit", 6).asDecimal(20).unsigned();
            t.money("balance", 6).asDecimal(20);
        });

        db.insert("insert into lines values (?, ?, ?)", 1010L, new BigDecimal("1E+3"), new BigDecimal("-12.5"));

        Map<String, Object> row = db.select("select * from lines").get(0);
        assertEquals(1010L, ((Number) row.get("cents")).longValue());
        assertEquals(new BigDecimal("1000.000000"), row.get("debit"));
        assertEquals(new BigDecimal("-12.500000"), row.get("balance"));
        assertEquals("bigint", db.scalar("select data_type from information_schema.columns where table_name = 'lines'"
                + " and column_name = 'cents'").get());
        assertThrows(QueryException.class, () -> db.insert("insert into lines values (-1, 0, 0)"));
        assertThrows(QueryException.class, () -> db.insert("insert into lines values (1, -1, 0)"));
    }
}

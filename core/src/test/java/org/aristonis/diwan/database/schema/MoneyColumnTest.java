package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MoneyColumnTest {

    @Test
    void moneyColumnsAreMinorUnitsUnlessAskedForDecimal() {
        Blueprint table = new Blueprint("ledger", "", true);
        ColumnDefinition cents = table.money("amount", 2);
        ColumnDefinition exact = table.money("debit", 6).asDecimal(20);

        assertEquals(ColumnType.MONEY, cents.type());
        assertEquals(2, cents.places());
        assertEquals(0, cents.total());
        assertEquals(20, exact.total());
        assertThrows(IllegalStateException.class, () -> table.string("name").asDecimal(10));
        assertThrows(IllegalArgumentException.class, () -> table.money("bad", 19));
        assertThrows(IllegalArgumentException.class, () -> table.money("bad2", 6).asDecimal(6));
    }
}

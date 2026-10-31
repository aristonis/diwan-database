package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import org.aristonis.diwan.database.Connection;
import org.junit.jupiter.api.Test;

class ChecksBlueprintTest {

    private static Blueprint creating() {
        return new Blueprint("lines", "", true);
    }

    @Test
    void columnChecksAndGeneratedExpressionsAreKept() {
        Blueprint table = creating();
        ColumnDefinition amount = table.decimal("amount", 12, 2).check("amount <> 0").check("amount < 1000000");
        ColumnDefinition total = table.decimal("total", 12, 2).storedAs("qty * price");
        ColumnDefinition label = table.string("label").virtualAs("upper(name)");

        table.prepare(63);

        assertEquals(Arrays.asList("amount <> 0", "amount < 1000000"), amount.checks());
        assertEquals("qty * price", total.storedAs().get());
        assertTrue(total.isGenerated());
        assertEquals("upper(name)", label.virtualAs().get());
        assertFalse(amount.isGenerated());
    }

    @Test
    void aTableCheckAndItsDropBecomeCommands() {
        Blueprint table = new Blueprint("entries", "", false);
        table.check("entries_side_check", "side in ('debit', 'credit')");
        table.dropCheck("old_check");

        table.prepare(63);

        Command check = table.commands().get(0);
        assertEquals(Command.CHECK, check.name());
        assertEquals("entries_side_check", check.string(Command.NAME));
        assertEquals("side in ('debit', 'credit')", check.string(Command.SQL));
        assertEquals(Command.DROP_CHECK, table.commands().get(1).name());
    }

    @Test
    void aRawColumnKeepsItsDefinition() {
        Blueprint table = creating();
        ColumnDefinition raw = table.rawColumn("flags", "integer default 0");

        assertEquals(ColumnType.RAW, raw.type());
        assertEquals("integer default 0", raw.rawDefinition().get());
        assertFalse(table.string("name").rawDefinition().isPresent());
    }

    @Test
    void impossibleGeneratedColumnsAreRefused() {
        Blueprint both = creating();
        both.integer("a").virtualAs("1").storedAs("2");
        Blueprint withDefault = creating();
        withDefault.integer("a").storedAs("1").defaultValue(3);
        Blueprint withCurrent = creating();
        withCurrent.timestamp("a").useCurrent().virtualAs("1");
        Blueprint withIncrement = creating();
        withIncrement.integer("a").autoIncrement().storedAs("1");
        Blueprint withPrimary = creating();
        withPrimary.integer("a").primary().storedAs("1");

        for (Blueprint table : Arrays.asList(both, withDefault, withCurrent, withIncrement, withPrimary)) {
            assertThrows(IllegalArgumentException.class, () -> table.prepare(63));
        }
    }

    @Test
    void blankSqlIsRefused() {
        Blueprint table = creating();

        assertThrows(IllegalArgumentException.class, () -> table.integer("a").check(" "));
        assertThrows(IllegalArgumentException.class, () -> table.integer("b").storedAs(""));
        assertThrows(IllegalArgumentException.class, () -> table.check("c", "\t"));
        assertThrows(NullPointerException.class, () -> table.rawColumn("d", null));
    }

    @Test
    void aGrammarThatCannotWriteChecksRefusesThemInsteadOfDroppingThem() {
        SchemaGrammar grammar = new SchemaGrammar() {
        };
        Blueprint table = creating();
        table.integer("a").check("a > 0");
        table.prepare(63);

        assertThrows(UnsupportedOperationException.class, () -> grammar.compile(table, unusedConnection()));
    }

    private static Connection unusedConnection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    throw new AssertionError("the grammar should refuse before using the connection");
                });
    }

    @Test
    void theBaseGrammarNamesNoDatabase() {
        SchemaGrammar grammar = new SchemaGrammar() {
        };

        assertThrows(UnsupportedOperationException.class, grammar::driverName);
    }
}

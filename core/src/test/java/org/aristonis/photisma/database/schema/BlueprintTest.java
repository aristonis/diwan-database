package org.aristonis.photisma.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlueprintTest {

    private static Blueprint creating() {
        return new Blueprint("users", "", true);
    }

    @Test
    void columnsHaveLaravelsDefaults() {
        Blueprint table = creating();

        ColumnDefinition id = table.id();
        ColumnDefinition name = table.string("name");
        ColumnDefinition code = table.string("code", 10);
        ColumnDefinition price = table.decimal("price");
        ColumnDefinition exact = table.decimal("exact", 12, 4);
        List<ColumnDefinition> stamps = table.timestamps();

        assertEquals("id", id.name());
        assertEquals(ColumnType.BIG_INTEGER, id.type());
        assertTrue(id.isAutoIncrement());
        assertTrue(id.isUnsigned());
        assertEquals(255, name.length());
        assertFalse(name.isNullable());
        assertEquals(10, code.length());
        assertEquals(8, price.total());
        assertEquals(2, price.places());
        assertEquals(12, exact.total());
        assertEquals(4, exact.places());
        assertEquals(Arrays.asList("created_at", "updated_at"), Arrays.asList(stamps.get(0).name(), stamps.get(1).name()));
        assertTrue(stamps.get(0).isNullable());
        assertEquals(ColumnType.TIMESTAMP, stamps.get(1).type());
        assertEquals(ColumnType.TEXT, table.text("bio").type());
        assertEquals(ColumnType.INTEGER, table.integer("age").type());
        assertEquals(ColumnType.BIG_INTEGER, table.bigInteger("views").type());
        assertEquals(ColumnType.BOOLEAN, table.booleanColumn("active").type());
        assertEquals(ColumnType.DATE, table.date("born").type());
        assertEquals(ColumnType.JSON, table.json("meta").type());
        assertEquals("users", table.table());
        assertTrue(table.isCreating());
    }

    @Test
    void modifiersAreRecorded() {
        ColumnDefinition column = creating().string("email")
                .nullable().defaultValue("x").unique().unsigned().comment("mail");

        assertTrue(column.isNullable());
        assertEquals("x", column.defaultValue().get());
        assertTrue(column.isUnique());
        assertTrue(column.isUnsigned());
        assertEquals("mail", column.comment().get());
        assertFalse(column.nullable(false).isNullable());
        assertEquals(Expression.NULL, column.defaultValue(null).defaultValue().get());
    }

    @Test
    void defaultsAcceptOnlyValuesThatCanBeWrittenAsSql() {
        ColumnDefinition column = creating().decimal("amount");

        column.defaultValue(1).defaultValue(2L).defaultValue((short) 3).defaultValue((byte) 4)
                .defaultValue(new BigDecimal("1.50")).defaultValue(java.math.BigInteger.TEN)
                .defaultValue(1.5d).defaultValue(2.5f).defaultValue(true).defaultValue(Expression.raw("0"));

        assertThrows(IllegalArgumentException.class, () -> column.defaultValue(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> column.defaultValue(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> column.defaultValue(new Object()));
    }

    @Test
    void useCurrentAndAutoIncrementApplyOnlyToTheirTypes() {
        Blueprint table = creating();

        assertEquals(Expression.CURRENT_DATE, table.date("d").useCurrent().defaultValue().get());
        assertEquals(Expression.CURRENT_TIMESTAMP, table.timestamp("t").useCurrent().defaultValue().get());
        assertThrows(IllegalStateException.class, () -> table.string("s").useCurrent());
        assertTrue(table.integer("n").autoIncrement().isAutoIncrement());
        assertThrows(IllegalStateException.class, () -> table.text("x").autoIncrement());
    }

    @Test
    void refusesInvalidColumns() {
        Blueprint table = creating();

        assertThrows(IllegalArgumentException.class, () -> table.string("s", 0));
        assertThrows(IllegalArgumentException.class, () -> table.decimal("d", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> table.decimal("d", 5, 6));
        assertThrows(IllegalArgumentException.class, () -> table.decimal("d", 5, -1));
        assertThrows(IllegalArgumentException.class, () -> table.text(""));
        assertThrows(IllegalArgumentException.class, () -> table.text("a\u0000b"));
        assertThrows(NullPointerException.class, () -> table.text(null));
        assertThrows(IllegalArgumentException.class, () -> table.text("x").unique(""));
        assertThrows(NullPointerException.class, () -> table.text("y").comment(null));
    }

    @Test
    void prepareRefusesEmptyTablesAndDuplicateColumns() {
        assertThrows(IllegalArgumentException.class, () -> creating().prepare(63));

        Blueprint duplicate = creating();
        duplicate.string("Email");
        duplicate.string("email");
        assertThrows(IllegalArgumentException.class, () -> duplicate.prepare(63));
    }

    @Test
    void prepareFreezesColumnsAndAddsImpliedCommands() {
        Blueprint table = new Blueprint("app_users", "app_", false);
        ColumnDefinition email = table.string("email").unique();
        table.string("nick").unique("nick_idx");

        table.prepare(63);
        table.prepare(63);

        assertThrows(IllegalStateException.class, email::nullable);
        assertThrows(IllegalStateException.class, () -> table.text("late"));
        List<Command> commands = table.commands();
        assertEquals(Arrays.asList("add", "add", "unique", "unique"),
                Arrays.asList(commands.get(0).name(), commands.get(1).name(), commands.get(2).name(),
                        commands.get(3).name()));
        assertEquals("app_users_email_unique", commands.get(2).string(Command.INDEX));
        assertEquals("nick_idx", commands.get(3).string(Command.INDEX));
        assertEquals(Arrays.asList("email"), commands.get(2).strings(Command.COLUMNS));
        assertEquals(email, commands.get(0).column());
    }

    @Test
    void longGeneratedIndexNamesAreRefusedButExplicitNamesPass() {
        Blueprint table = creating();
        table.string("a_rather_long_column_name").unique();

        assertThrows(IllegalArgumentException.class, () -> table.prepare(20));

        Blueprint named = creating();
        named.string("a_rather_long_column_name").unique("short");
        named.prepare(20);
        assertEquals("short", named.commands().get(0).string(Command.INDEX));
    }

    @Test
    void repeatedOrTooLongIndexNamesAreRefused() {
        Blueprint repeated = creating();
        repeated.string("a").unique("same");
        repeated.string("b").unique("SAME");
        assertThrows(IllegalArgumentException.class, () -> repeated.prepare(63));

        Blueprint tooLong = creating();
        tooLong.string("a").unique("a_name_that_is_too_long");
        assertThrows(IllegalArgumentException.class, () -> tooLong.prepare(10));
    }

    @Test
    void generatedIndexNamesAreLowercaseWithDashesAndDotsReplaced() {
        Blueprint table = new Blueprint("My-Table", "", true);
        table.string("A.b").unique();

        table.prepare(63);

        assertEquals("my_table_a_b_unique", table.commands().get(0).string(Command.INDEX));
    }
}

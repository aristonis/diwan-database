package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangeColumnBlueprintTest {

    private static Blueprint altering() {
        return new Blueprint("posts", "", false);
    }

    private static List<String> names(Blueprint table) {
        List<String> names = new ArrayList<>();
        for (Command command : table.commands()) {
            names.add(command.name());
        }
        return names;
    }

    @Test
    void aChangedColumnBecomesAChangeCommandInTheAddSlot() {
        Blueprint table = altering();
        table.string("title", 200).nullable().change();
        table.string("slug");
        table.dropColumn("old");

        table.prepare(63);

        assertEquals(Arrays.asList(Command.CHANGE, Command.ADD, Command.DROP_COLUMN), names(table));
        ColumnDefinition changed = table.commands().get(0).column();
        assertTrue(changed.isChange());
        assertEquals(200, changed.length());
        assertTrue(changed.isNullable());
    }

    @Test
    void falseIndexFlagsDropTheConventionalIndexes() {
        Blueprint table = altering();
        table.string("code").unique(false).index(false).primary(false).change();

        table.prepare(63);

        assertEquals(Arrays.asList(Command.CHANGE, Command.DROP_PRIMARY, Command.DROP_UNIQUE, Command.DROP_INDEX),
                names(table));
        assertEquals("posts_primary", table.commands().get(1).string(Command.INDEX));
        assertEquals("posts_code_unique", table.commands().get(2).string(Command.INDEX));
        assertEquals("posts_code_index", table.commands().get(3).string(Command.INDEX));
        assertEquals(Arrays.asList("code"), table.commands().get(3).strings(Command.COLUMNS));
    }

    @Test
    void aTrueFlagAfterAFalseOneAddsTheIndexInstead() {
        Blueprint table = altering();
        table.string("code").unique(false).unique(true).index(false).index().primary(false).primary(true).change();

        table.prepare(63);

        assertEquals(Arrays.asList(Command.CHANGE, Command.PRIMARY, Command.UNIQUE, Command.PLAIN_INDEX),
                names(table));
    }

    @Test
    void thePrimaryKeyMovesBetweenColumnsInEitherOrder() {
        Blueprint table = altering();
        table.string("b").primary().change();
        table.string("a").primary(false).change();

        table.prepare(63);

        assertEquals(Arrays.asList(Command.CHANGE, Command.CHANGE, Command.DROP_PRIMARY, Command.PRIMARY),
                names(table));
    }

    @Test
    void falseFlagsWithoutChangeAreRefused() {
        Blueprint table = altering();
        table.string("code").unique(false);

        assertThrows(IllegalArgumentException.class, () -> table.prepare(63));
    }

    @Test
    void changeInsideCreateIsRefused() {
        Blueprint table = new Blueprint("posts", "", true);
        table.string("code").change();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> table.prepare(63));
        assertTrue(error.getMessage().contains("existing table"));
    }

    @Test
    void changeToAutoIncrementIsRefused() {
        Blueprint table = altering();
        table.increments("id").change();

        assertThrows(IllegalArgumentException.class, () -> table.prepare(63));
    }

    @Test
    void theSameColumnChangedTwiceIsRefused() {
        Blueprint table = altering();
        table.string("code").change();
        table.text("code").change();

        assertThrows(IllegalArgumentException.class, () -> table.prepare(63));
    }

    @Test
    void changeAndRenameOfOneColumnAreRefusedEitherWay() {
        Blueprint from = altering();
        from.string("code", 10).change();
        from.renameColumn("code", "slug");
        Blueprint to = altering();
        to.renameColumn("slug", "code");
        to.string("code", 10).change();

        assertThrows(IllegalArgumentException.class, () -> from.prepare(63));
        assertThrows(IllegalArgumentException.class, () -> to.prepare(63));
    }

    @Test
    void aPlainColumnIsNotAChange() {
        assertFalse(altering().string("code").isChange());
    }
}

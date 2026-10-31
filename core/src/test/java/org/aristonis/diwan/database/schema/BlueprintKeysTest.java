package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlueprintKeysTest {

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
    void indexesGetLaravelsDefaultNames() {
        Blueprint table = altering();
        table.index("user_id", "created_at");
        table.unique("slug");
        table.primary("id");

        table.prepare(63);

        assertEquals("posts_user_id_created_at_index", table.commands().get(0).string(Command.INDEX));
        assertEquals(Arrays.asList("user_id", "created_at"), table.commands().get(0).strings(Command.COLUMNS));
        assertEquals("posts_slug_unique", table.commands().get(1).string(Command.INDEX));
        assertEquals("posts_id_primary", table.commands().get(2).string(Command.INDEX));
    }

    @Test
    void chosenNamesAreKept() {
        Blueprint table = altering();
        table.index(Arrays.asList("a", "b"), "ab");
        table.unique(Arrays.asList("c"), "c_u");
        table.primary(Arrays.asList("id"), "pk");

        table.prepare(63);

        assertEquals(Arrays.asList("ab", "c_u", "pk"), Arrays.asList(table.commands().get(0).string(Command.INDEX),
                table.commands().get(1).string(Command.INDEX), table.commands().get(2).string(Command.INDEX)));
        assertFalse(table.commands().get(0).has(Command.FROM));
    }

    @Test
    void columnModifiersAddIndexesAfterExplicitCommands() {
        Blueprint table = altering();
        table.string("code").index();
        table.string("tag").index("tag_idx");
        table.dropColumn("old");

        table.prepare(63);

        assertEquals(Arrays.asList("add", "add", "dropColumn", "index", "index"), names(table));
        assertEquals("posts_code_index", table.commands().get(3).string(Command.INDEX));
        assertEquals("tag_idx", table.commands().get(4).string(Command.INDEX));
    }

    @Test
    void aColumnCanBeThePrimaryKey() {
        Blueprint table = new Blueprint("tags", "", true);
        ColumnDefinition code = table.string("code").primary();

        table.prepare(63);

        assertTrue(code.isPrimary());
        assertEquals(Arrays.asList("primary"), names(table));
        assertEquals(Arrays.asList("code"), table.commands().get(0).strings(Command.COLUMNS));
    }

    @Test
    void foreignKeysCarryTheirReferenceActionsAndPrefix() {
        Blueprint table = new Blueprint("app_posts", "app_", true);
        table.bigInteger("user_id");
        ForeignKeyDefinition key = table.foreign("user_id").references("id").on("users").cascadeOnDelete()
                .restrictOnUpdate();

        table.prepare(63);

        Command command = table.commands().get(0);
        assertEquals(Command.FOREIGN, command.name());
        assertSame(key, command.foreignKey());
        assertEquals("app_posts_user_id_foreign", key.name());
        assertEquals("app_posts_user_id_foreign", command.string(Command.INDEX));
        assertEquals("app_users", key.referencedTable());
        assertEquals(Arrays.asList("id"), key.referencedColumns());
        assertEquals(ForeignKeyAction.CASCADE, key.deleteAction().get());
        assertEquals(ForeignKeyAction.RESTRICT, key.updateAction().get());
        assertThrows(IllegalStateException.class, key::cascadeOnUpdate);
    }

    @Test
    void everyActionHelperSetsItsAction() {
        Blueprint table = altering();
        assertEquals(ForeignKeyAction.SET_NULL, table.foreign("a").nullOnDelete().deleteAction().get());
        assertEquals(ForeignKeyAction.RESTRICT, table.foreign("b").restrictOnDelete().deleteAction().get());
        assertEquals(ForeignKeyAction.NO_ACTION, table.foreign("c").noActionOnDelete().deleteAction().get());
        assertEquals(ForeignKeyAction.CASCADE, table.foreign("d").cascadeOnUpdate().updateAction().get());
        assertEquals(ForeignKeyAction.SET_NULL, table.foreign("e").nullOnUpdate().updateAction().get());
        assertEquals(ForeignKeyAction.NO_ACTION, table.foreign("f").noActionOnUpdate().updateAction().get());
        assertEquals(ForeignKeyAction.SET_DEFAULT, table.foreign("g").onDelete(ForeignKeyAction.SET_DEFAULT)
                .deleteAction().get());
        assertEquals("set null", ForeignKeyAction.SET_NULL.sql());
        assertFalse(table.foreign("h").deleteAction().isPresent());
    }

    @Test
    void aForeignKeyNameIsOnlyKnownOnceChecked() {
        Blueprint table = altering();
        ForeignKeyDefinition key = table.foreign(Arrays.asList("user_id"), "fk_user").references("id").on("users");

        assertThrows(IllegalStateException.class, key::name);
        table.prepare(63);
        assertEquals("fk_user", key.name());
    }

    @Test
    void incompleteForeignKeysAreRefused() {
        Blueprint noTable = altering();
        noTable.foreign("user_id").references("id");
        assertThrows(IllegalArgumentException.class, () -> noTable.prepare(63));

        Blueprint mismatch = altering();
        mismatch.foreign("a", "b").references("id").on("users");
        assertThrows(IllegalArgumentException.class, () -> mismatch.prepare(63));

        Blueprint noReference = altering();
        noReference.foreign("a").on("users");
        assertThrows(IllegalArgumentException.class, () -> noReference.prepare(63));
    }

    @Test
    void emptyOrRepeatedColumnListsAreRefused() {
        Blueprint table = altering();
        assertThrows(IllegalArgumentException.class, () -> table.index());
        assertThrows(IllegalArgumentException.class, () -> table.unique("a", "A"));
        assertThrows(IllegalArgumentException.class, () -> table.foreign("a").references());
        assertThrows(IllegalArgumentException.class, () -> table.dropColumn());
        assertThrows(IllegalArgumentException.class, () -> table.dropForeign(Collections.<String>emptyList()));
        assertThrows(NullPointerException.class, () -> table.index((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> table.renameColumn("a", ""));
    }

    @Test
    void foreignIdIsAnUnsignedBigIntegerThatConstrainsByPlural() {
        Blueprint table = new Blueprint("posts", "", true);
        ForeignIdColumnDefinition user = table.foreignId("user_id");
        ForeignKeyDefinition userKey = user.nullable().constrained().nullOnDelete();
        ForeignKeyDefinition categoryKey = table.foreignId("blog_category_id").constrained();
        ForeignKeyDefinition authorKey = table.foreignId("author_id").constrained("people");
        ForeignKeyDefinition codeKey = table.foreignId("tag_code").constrained("tags", "code");
        ForeignKeyDefinition plainKey = table.foreignId("owner").references("uid").on("owners");

        table.prepare(63);

        assertEquals(ColumnType.BIG_INTEGER, user.type());
        assertTrue(user.isUnsigned());
        assertTrue(user.isNullable());
        assertEquals("users", userKey.referencedTable());
        assertEquals(Arrays.asList("id"), userKey.referencedColumns());
        assertEquals("blog_categories", categoryKey.referencedTable());
        assertEquals("people", authorKey.referencedTable());
        assertEquals(Arrays.asList("code"), codeKey.referencedColumns());
        assertEquals("owners", plainKey.referencedTable());
    }

    @Test
    void foreignIdModifiersKeepTheirType() {
        Blueprint table = altering();
        ForeignIdColumnDefinition column = table.foreignId("x_id").nullable(false).defaultValue(1).unique()
                .unique("u").index().index("i").primary().unsigned().comment("c");

        assertTrue(column.isIndexed());
        assertTrue(column.isPrimary());
        assertEquals("c", column.comment().get());
    }

    @Test
    void dropsByColumnsUseDefaultNamesAndKeepTheColumns() {
        Blueprint table = altering();
        table.dropIndex(Arrays.asList("a", "b"));
        table.dropUnique(Arrays.asList("slug"));
        table.dropForeign(Arrays.asList("user_id"));
        table.dropIndex("by_name");
        table.dropUnique("u_name");
        table.dropForeign("f_name");
        table.dropPrimary();
        table.dropPrimary("pk");

        table.prepare(63);

        List<Command> commands = table.commands();
        assertEquals("posts_a_b_index", commands.get(0).string(Command.INDEX));
        assertEquals(Arrays.asList("a", "b"), commands.get(0).strings(Command.COLUMNS));
        assertEquals("posts_slug_unique", commands.get(1).string(Command.INDEX));
        assertEquals("posts_user_id_foreign", commands.get(2).string(Command.INDEX));
        assertFalse(commands.get(5).has(Command.COLUMNS));
        assertEquals("posts_primary", commands.get(6).string(Command.INDEX));
        assertEquals("pk", commands.get(7).string(Command.INDEX));
    }

    @Test
    void dropConstrainedForeignIdDropsTheKeyThenTheColumn() {
        Blueprint table = altering();
        table.dropConstrainedForeignId("user_id");
        table.renameIndex("old", "new");
        table.renameColumn("title", "headline");

        table.prepare(63);

        assertEquals(Arrays.asList("dropForeign", "dropColumn", "renameIndex", "renameColumn"), names(table));
        assertEquals(Arrays.asList("user_id"), table.commands().get(1).strings(Command.COLUMNS));
        assertEquals("old", table.commands().get(2).string(Command.FROM));
        assertEquals("headline", table.commands().get(3).string(Command.TO));
    }

    @Test
    void aCheckedBlueprintRefusesNewCommands() {
        Blueprint table = altering();
        table.prepare(63);

        assertThrows(IllegalStateException.class, () -> table.index("a"));
        assertThrows(IllegalStateException.class, () -> table.foreign("a"));
        assertThrows(IllegalStateException.class, () -> table.dropColumn("a"));
        assertThrows(IllegalStateException.class, () -> table.dropIndex("a"));
        assertThrows(IllegalStateException.class, () -> table.renameIndex("a", "b"));
        assertThrows(IllegalStateException.class, () -> table.foreignId("a"));
    }

    @Test
    void longGeneratedNamesAskForAName() {
        Blueprint table = altering();
        table.index("a_long_column_name");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> table.prepare(10));
        assertTrue(thrown.getMessage().contains("pass a name"));

        Blueprint foreign = altering();
        foreign.foreign("a_long_column_name").references("id").on("t");
        assertThrows(IllegalArgumentException.class, () -> foreign.prepare(10));
    }

    @Test
    void repeatedIndexNamesAcrossColumnsAndCommandsAreRefused() {
        Blueprint table = altering();
        table.index(Arrays.asList("a"), "same");
        table.string("b").unique("SAME");

        assertThrows(IllegalArgumentException.class, () -> table.prepare(63));
    }
}

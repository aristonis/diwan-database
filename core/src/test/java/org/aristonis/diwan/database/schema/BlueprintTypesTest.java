package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlueprintTypesTest {

    private static Blueprint creating() {
        return new Blueprint("users", "", true);
    }

    private static List<String> names(Blueprint table) {
        List<String> names = new ArrayList<>();
        for (ColumnDefinition column : table.columns()) {
            names.add(column.name());
        }
        return names;
    }

    @Test
    void everyTypeMethodUsesItsType() {
        Blueprint t = creating();
        assertEquals(ColumnType.TINY_INTEGER, t.tinyInteger("a").type());
        assertEquals(ColumnType.SMALL_INTEGER, t.smallInteger("b").type());
        assertEquals(ColumnType.MEDIUM_INTEGER, t.mediumInteger("c").type());
        assertEquals(ColumnType.DOUBLE, t.doubleColumn("d").type());
        assertEquals(ColumnType.TINY_TEXT, t.tinyText("e").type());
        assertEquals(ColumnType.MEDIUM_TEXT, t.mediumText("f").type());
        assertEquals(ColumnType.LONG_TEXT, t.longText("g").type());
        assertEquals(ColumnType.YEAR, t.year("h").type());
        assertEquals(ColumnType.BINARY, t.binary("i").type());
        assertEquals(ColumnType.JSONB, t.jsonb("j").type());
        assertEquals(ColumnType.DATE_TIME, t.dateTime("k").type());
        assertEquals(ColumnType.DATE_TIME_TZ, t.dateTimeTz("l").type());
        assertEquals(ColumnType.TIME, t.time("m").type());
        assertEquals(ColumnType.TIME_TZ, t.timeTz("n").type());
        assertEquals(ColumnType.TIMESTAMP_TZ, t.timestampTz("o").type());
        assertEquals(ColumnType.FLOAT, t.floatColumn("p").type());
        assertEquals(ColumnType.CHAR, t.charColumn("q").type());
        assertEquals(ColumnType.SET, t.set("r", Arrays.asList("x", "y")).type());
    }

    @Test
    void defaultsFollowLaravel() {
        Blueprint t = creating();

        assertEquals(53, t.floatColumn("f").precision().getAsInt());
        assertEquals(255, t.charColumn("c").length());
        assertEquals(26, t.ulid().length());
        assertEquals(ColumnType.CHAR, t.ulid("other").type());
        assertEquals("uuid", t.uuid().name());
        assertEquals("ip_address", t.ipAddress().name());
        assertEquals("mac_address", t.macAddress().name());
        assertFalse(t.time("plain").precision().isPresent());
        assertEquals(3, t.dateTime("dt", 3).precision().getAsInt());
        assertEquals(6, t.timestamp("ts", 6).precision().getAsInt());
        assertEquals(0, t.timeTz("tz", 0).precision().getAsInt());
        assertEquals(2, t.dateTimeTz("dtz", 2).precision().getAsInt());
        assertEquals(1, t.time("tm", 1).precision().getAsInt());
        assertEquals(4, t.timestampTz("tstz", 4).precision().getAsInt());
        assertTrue(t.text("x").allowed().isEmpty());
    }

    @Test
    void unsignedAndIncrementsColumns() {
        Blueprint t = creating();
        assertTrue(t.unsignedTinyInteger("a").isUnsigned());
        assertTrue(t.unsignedSmallInteger("b").isUnsigned());
        assertTrue(t.unsignedMediumInteger("c").isUnsigned());
        assertTrue(t.unsignedInteger("d").isUnsigned());
        assertEquals(ColumnType.BIG_INTEGER, t.unsignedBigInteger("e").type());

        for (ColumnDefinition key : Arrays.asList(t.increments("i1"), t.integerIncrements("i2"), t.tinyIncrements("i3"),
                t.smallIncrements("i4"), t.mediumIncrements("i5"), t.bigIncrements("i6"))) {
            assertTrue(key.isAutoIncrement());
            assertTrue(key.isUnsigned());
        }
        assertEquals(ColumnType.INTEGER, t.increments("i7").type());
    }

    @Test
    void useCurrentPicksTheRightDefault() {
        Blueprint t = creating();
        assertEquals(Expression.CURRENT_TIME, t.time("a").useCurrent().defaultValue().get());
        assertEquals(Expression.CURRENT_TIME, t.timeTz("b").useCurrent().defaultValue().get());
        assertEquals(Expression.CURRENT_TIMESTAMP, t.dateTime("c").useCurrent().defaultValue().get());
        assertEquals(Expression.CURRENT_TIMESTAMP, t.timestampTz("d").useCurrent().defaultValue().get());
        ColumnDefinition year = t.year("y").useCurrent();
        assertTrue(year.usesCurrent());
        assertFalse(year.defaultValue().isPresent());
        assertFalse(t.year("z").useCurrent().defaultValue(2000).usesCurrent());
        assertThrows(IllegalStateException.class, () -> t.binary("bin").useCurrent());
    }

    @Test
    void enumKeepsItsValuesInOrder() {
        ColumnDefinition status = creating().enumColumn("status", Arrays.asList("active", "o'k", "banned"));

        assertEquals(ColumnType.ENUM, status.type());
        assertEquals(Arrays.asList("active", "o'k", "banned"), status.allowed());
        assertThrows(UnsupportedOperationException.class, () -> status.allowed().add("x"));
    }

    @Test
    void invalidValuesAndRangesAreRefused() {
        Blueprint t = creating();
        assertThrows(IllegalArgumentException.class, () -> t.enumColumn("a", Collections.<String>emptyList()));
        assertThrows(IllegalArgumentException.class, () -> t.enumColumn("a", Arrays.asList("x", null)));
        assertThrows(IllegalArgumentException.class, () -> t.set("a", Arrays.asList("x", "x")));
        assertThrows(NullPointerException.class, () -> t.enumColumn("a", null));
        assertThrows(IllegalArgumentException.class, () -> t.charColumn("a", 0));
        assertThrows(IllegalArgumentException.class, () -> t.floatColumn("a", 0));
        assertThrows(IllegalArgumentException.class, () -> t.floatColumn("a", 54));
        assertThrows(IllegalArgumentException.class, () -> t.time("a", 7));
        assertThrows(IllegalArgumentException.class, () -> t.timestamp("a", -1));
        assertTrue(t.columns().isEmpty());
    }

    @Test
    void timestampHelpersAreNullablePairs() {
        Blueprint t = creating();
        List<ColumnDefinition> tz = t.timestampsTz();
        List<ColumnDefinition> plain = new Blueprint("b", "", true).nullableTimestamps();
        List<ColumnDefinition> datetimes = new Blueprint("c", "", true).datetimes();

        assertEquals(ColumnType.TIMESTAMP_TZ, tz.get(0).type());
        assertTrue(tz.get(1).isNullable());
        assertEquals(ColumnType.TIMESTAMP, plain.get(0).type());
        assertEquals(ColumnType.DATE_TIME, datetimes.get(1).type());
        assertEquals("updated_at", datetimes.get(1).name());
    }

    @Test
    void softDeletesAndRememberToken() {
        Blueprint t = creating();
        ColumnDefinition deleted = t.softDeletes();
        ColumnDefinition token = t.rememberToken();

        assertEquals("deleted_at", deleted.name());
        assertTrue(deleted.isNullable());
        assertEquals("remember_token", token.name());
        assertEquals(100, token.length());
        assertTrue(token.isNullable());
        assertEquals(ColumnType.TIMESTAMP_TZ, new Blueprint("a", "", true).softDeletesTz().type());
        assertEquals(ColumnType.DATE_TIME, new Blueprint("b", "", true).softDeletesDatetime().type());
        assertEquals("gone", new Blueprint("c", "", true).softDeletes("gone").name());
        assertEquals("gone", new Blueprint("d", "", true).softDeletesTz("gone").name());
        assertEquals("gone", new Blueprint("e", "", true).softDeletesDatetime("gone").name());
    }

    @Test
    void foreignUuidAndUlidConstrainLikeForeignId() {
        Blueprint t = creating();
        ForeignIdColumnDefinition author = t.foreignUuid("author_id");
        ForeignKeyDefinition authorKey = author.constrained();
        ForeignIdColumnDefinition team = t.foreignUlid("team_id");
        team.constrained("teams");

        t.prepare(63);

        assertEquals(ColumnType.UUID, author.type());
        assertFalse(author.isUnsigned());
        assertEquals("authors", authorKey.referencedTable());
        assertEquals(ColumnType.CHAR, team.type());
        assertEquals(26, team.length());
    }

    @Test
    void morphsAddTypeIdAndIndex() {
        Blueprint t = new Blueprint("comments", "", true);
        t.morphs("commentable");
        t.nullableUuidMorphs("owner", "owner_idx");
        t.ulidMorphs("target");
        t.nullableMorphs("a");
        t.numericMorphs("b");
        t.nullableNumericMorphs("c");
        t.uuidMorphs("d");
        t.nullableUlidMorphs("e");

        t.prepare(63);

        assertEquals(Arrays.asList("commentable_type", "commentable_id", "owner_type", "owner_id"),
                names(t).subList(0, 4));
        assertEquals(ColumnType.BIG_INTEGER, t.columns().get(1).type());
        assertTrue(t.columns().get(1).isUnsigned());
        assertEquals(ColumnType.UUID, t.columns().get(3).type());
        assertTrue(t.columns().get(3).isNullable());
        assertEquals(26, t.columns().get(5).length());
        assertEquals("comments_commentable_type_commentable_id_index", t.commands().get(0).string(Command.INDEX));
        assertEquals("owner_idx", t.commands().get(1).string(Command.INDEX));
        assertEquals(8, t.commands().size());
    }

    @Test
    void dropHelpersDropTheirColumnsAndIndex() {
        Blueprint t = new Blueprint("comments", "", false);
        t.dropTimestamps();
        t.dropTimestampsTz();
        t.dropSoftDeletes();
        t.dropSoftDeletesTz("gone");
        t.dropRememberToken();
        t.dropMorphs("commentable");
        t.dropMorphs("owner", "owner_idx");

        t.prepare(63);

        List<Command> commands = t.commands();
        assertEquals(Arrays.asList("created_at", "updated_at"), commands.get(0).strings(Command.COLUMNS));
        assertEquals(Arrays.asList("deleted_at"), commands.get(2).strings(Command.COLUMNS));
        assertEquals(Arrays.asList("gone"), commands.get(3).strings(Command.COLUMNS));
        assertEquals(Arrays.asList("remember_token"), commands.get(4).strings(Command.COLUMNS));
        assertEquals("comments_commentable_type_commentable_id_index", commands.get(5).string(Command.INDEX));
        assertEquals(Arrays.asList("commentable_type", "commentable_id"), commands.get(6).strings(Command.COLUMNS));
        assertEquals("owner_idx", commands.get(7).string(Command.INDEX));
        assertEquals(Command.DROP_INDEX, commands.get(7).name());
    }

    @Test
    void typeGroupsAreKnown() {
        assertTrue(ColumnType.MEDIUM_INTEGER.isInteger());
        assertFalse(ColumnType.FLOAT.isInteger());
        assertTrue(ColumnType.YEAR.isTemporal());
        assertFalse(ColumnType.UUID.isTemporal());
    }
}

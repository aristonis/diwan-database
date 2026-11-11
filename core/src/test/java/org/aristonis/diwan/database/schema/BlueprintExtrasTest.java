package org.aristonis.diwan.database.schema;

import org.aristonis.diwan.database.schema.grammar.Command;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Covers the temporary table, raw index, table comment and foreignIdFor parts of {@link Blueprint}. */
class BlueprintExtrasTest {

    /** A key source with fixed values. */
    private static final class Source implements ForeignKeySource {
        private final String foreignKey;
        private final String table;
        private final String keyName;
        private final KeyKind keyKind;

        Source(String foreignKey, String table, String keyName, KeyKind keyKind) {
            this.foreignKey = foreignKey;
            this.table = table;
            this.keyName = keyName;
            this.keyKind = keyKind;
        }

        @Override
        public String foreignKey() {
            return foreignKey;
        }

        @Override
        public String table() {
            return table;
        }

        @Override
        public String keyName() {
            return keyName;
        }

        @Override
        public KeyKind keyKind() {
            return keyKind;
        }
    }

    @TempDir
    Path folder;

    private Connection db;

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("test.db"));
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
    }

    private List<String> sql(Consumer<Schema> work) {
        List<String> statements = new ArrayList<>();
        for (LoggedQuery query : db.pretend(p -> work.accept(Schema.of(p, new SchemaTest.TestGrammar())))) {
            statements.add(query.sql());
        }
        return statements;
    }

    private static Blueprint creating() {
        return new Blueprint("posts", "", true);
    }

    private static Command commandNamed(Blueprint table, String name) {
        for (Command command : table.commands()) {
            if (name.equals(command.name())) {
                return command;
            }
        }
        throw new AssertionError("No command " + name + " in " + table.commands());
    }

    @Test
    void temporaryOnlyOnCreate() {
        Blueprint created = creating();
        assertFalse(created.isTemporary());

        created.temporary();

        assertTrue(created.isTemporary());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new Blueprint("posts", "", false).temporary());
        assertTrue(error.getMessage().contains("Schema.create"));
        Schema schema = Schema.of(db, new SchemaTest.TestGrammar());
        assertThrows(IllegalStateException.class, () -> schema.table("posts", t -> t.temporary()));
    }

    @Test
    void temporaryRefusedOnceTheBlueprintIsClosed() {
        Blueprint table = creating();
        table.string("name");
        table.prepare(63);

        assertThrows(IllegalStateException.class, table::temporary);
    }

    @Test
    void rawIndexNeedsAName() {
        Blueprint table = creating();

        assertThrows(IllegalArgumentException.class, () -> table.rawIndex("lower(email)", ""));
        assertThrows(IllegalArgumentException.class, () -> table.rawIndex("lower(email)", "  "));
        assertThrows(NullPointerException.class, () -> table.rawIndex("lower(email)", null));
        assertThrows(IllegalArgumentException.class, () -> table.rawIndex(" ", "name"));
        assertThrows(NullPointerException.class, () -> table.rawIndex(null, "name"));
        assertTrue(table.commands().isEmpty());
    }

    @Test
    void rawIndexKeepsItsExpressionAndFlag() {
        Blueprint table = new Blueprint("users", "", false);
        table.rawIndex("lower(email)", "users_email_lower");

        table.prepare(63);

        Command index = table.commands().get(0);
        assertEquals(Command.PLAIN_INDEX, index.name());
        assertEquals("users_email_lower", index.string(Command.INDEX));
        assertEquals(Collections.singletonList("lower(email)"), index.strings(Command.COLUMNS));
        assertTrue(index.has(Command.RAW));
    }

    @Test
    void rawIndexIsWrittenUnquoted() {
        List<String> statements = sql(schema -> schema.table("users",
                t -> t.rawIndex("lower(email)", "users_email_lower")));

        assertEquals(Arrays.asList("create index \"users_email_lower\" on \"users\" (lower(email))"), statements);
    }

    @Test
    void rawIndexIsFluent() {
        List<String> statements = sql(schema -> schema.table("users",
                t -> t.rawIndex("lower(email)", "users_email_lower").whereNotNull("email")));

        assertEquals(Arrays.asList(
                "create index \"users_email_lower\" on \"users\" (lower(email)) where \"email\" is not null"),
                statements);
    }

    @Test
    void rawIndexRefusesOrderAndCollation() {
        Blueprint table = creating();
        IndexDefinition index = table.rawIndex("lower(email)", "posts_email_lower");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> index.desc("lower(email)"));
        assertEquals("Put the order and collation of a raw index inside its expression", error.getMessage());
        assertThrows(IllegalStateException.class, () -> index.asc("lower(email)"));
        assertThrows(IllegalStateException.class, () -> index.collate("lower(email)", "nocase"));
    }

    @Test
    void aColumnIndexStillTakesOrder() {
        Blueprint table = creating();

        IndexDefinition index = table.index("email").desc("email");

        assertEquals(Collections.singletonList("email"), index.columns());
    }

    @Test
    void rawIndexNameClash() {
        Blueprint clash = creating();
        clash.string("email");
        clash.rawIndex("lower(email)", "same_name");
        clash.index(Arrays.asList("email"), "same_name");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> clash.prepare(63));
        assertEquals("The same index name is used twice", error.getMessage());

        Blueprint twice = creating();
        twice.string("a");
        twice.rawIndex("lower(a)", "SAME");
        twice.rawIndex("lower(b)", "same");
        assertThrows(IllegalArgumentException.class, () -> twice.prepare(63));

        Blueprint tooLong = creating();
        tooLong.string("a");
        tooLong.rawIndex("lower(a)", "n".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> tooLong.prepare(63));
    }

    @Test
    void tableCommentBecomesACommand() {
        Blueprint table = new Blueprint("users", "", false);
        table.comment("it's a table");

        table.prepare(63);

        Command comment = table.commands().get(0);
        assertEquals(Command.TABLE_COMMENT, comment.name());
        assertEquals("it's a table", comment.string(Command.COMMENT));
        assertThrows(NullPointerException.class, () -> new Blueprint("users", "", false).comment(null));
    }

    @Test
    void tableCommentWritesNothingInTheBaseGrammar() {
        List<String> statements = sql(schema -> schema.create("t", t -> {
            t.string("name");
            t.comment("notes");
        }));

        assertEquals(Arrays.asList("create table \"t\" (\"name\" STRING)"), statements);
    }

    @Test
    void foreignIdForIntegerKey() {
        Blueprint table = creating();
        Source user = new Source("user_id", "users", "id", ForeignKeySource.KeyKind.INTEGER);

        ForeignIdColumnDefinition column = table.foreignIdFor(user);
        table.prepare(63);

        assertEquals("user_id", column.name());
        assertEquals(ColumnType.BIG_INTEGER, column.type());
        assertTrue(column.isUnsigned());
        for (Command command : table.commands()) {
            assertFalse(Command.FOREIGN.equals(command.name()));
        }
    }

    @Test
    void foreignIdForUuidAndUlid() {
        Blueprint table = creating();

        ForeignIdColumnDefinition uuid = table.foreignIdFor(
                new Source("team_id", "teams", "id", ForeignKeySource.KeyKind.UUID));
        ForeignIdColumnDefinition ulid = table.foreignIdFor(
                new Source("order_id", "orders", "id", ForeignKeySource.KeyKind.ULID));

        assertEquals(ColumnType.UUID, uuid.type());
        assertEquals(ColumnType.CHAR, ulid.type());
        assertEquals(26, ulid.length());
    }

    @Test
    void foreignIdForColumnName() {
        Blueprint table = creating();
        Source user = new Source("user_id", "users", "id", ForeignKeySource.KeyKind.INTEGER);

        ForeignIdColumnDefinition column = table.foreignIdFor(user, "author_id");
        ForeignIdColumnDefinition fallback = table.foreignIdFor(user, null);

        assertEquals("author_id", column.name());
        assertEquals("user_id", fallback.name());
    }

    @Test
    void foreignIdForRejectsNullSource() {
        Blueprint table = creating();

        assertThrows(NullPointerException.class, () -> table.foreignIdFor(null));
        assertThrows(NullPointerException.class, () -> table.foreignIdFor(null, "x"));
        assertThrows(NullPointerException.class,
                () -> table.foreignIdFor(new Source("a_id", "as", "id", null)));
    }

    @Test
    void foreignIdForConstrainedUsesTheSourceTableAndKey() {
        Blueprint table = creating();
        Source member = new Source("owner_ref", "members", "member_key", ForeignKeySource.KeyKind.INTEGER);

        ForeignKeyDefinition key = table.foreignIdFor(member).constrained();
        ForeignKeyDefinition named = table.foreignIdFor(member, "other_ref").constrained("people");
        ForeignKeyDefinition explicit = table.foreignIdFor(member, "third_ref").constrained("groups", "gid");
        table.prepare(63);

        assertEquals("members", key.referencedTable());
        assertEquals(Collections.singletonList("member_key"), key.referencedColumns());
        assertEquals("people", named.referencedTable());
        assertEquals(Collections.singletonList("member_key"), named.referencedColumns());
        assertEquals("groups", explicit.referencedTable());
        assertEquals(Collections.singletonList("gid"), explicit.referencedColumns());
        assertEquals(Collections.singletonList("owner_ref"), commandNamed(table, Command.FOREIGN)
                .strings(Command.COLUMNS));
    }

    @Test
    void foreignIdKeepsGuessingWithoutASource() {
        Blueprint table = creating();

        ForeignKeyDefinition key = table.foreignId("user_id").constrained();
        ForeignKeyDefinition people = table.foreignId("boss_id").constrained("people");

        assertEquals("users", key.referencedTable());
        assertEquals(Collections.singletonList("id"), key.referencedColumns());
        assertEquals(Collections.singletonList("id"), people.referencedColumns());
    }
}

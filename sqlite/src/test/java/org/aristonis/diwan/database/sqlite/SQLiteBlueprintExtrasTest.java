package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.schema.ForeignKeyInfo;
import org.aristonis.diwan.database.schema.ForeignKeySource;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Temporary tables, expression indexes, table comments and foreignIdFor on SQLite. */
class SQLiteBlueprintExtrasTest {

    private static final String RAW_INDEX = "users_email_lower";

    /** A key source with fixed values. */
    private static final class Source implements ForeignKeySource {
        private final String foreignKey;
        private final String table;
        private final String keyName;

        Source(String foreignKey, String table, String keyName) {
            this.foreignKey = foreignKey;
            this.table = table;
            this.keyName = keyName;
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
            return KeyKind.INTEGER;
        }
    }

    @TempDir
    Path folder;

    private SQLiteDataSource dataSource;
    private Connection db;
    private Schema schema;
    private Schema old;

    @BeforeEach
    void connect() {
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("extras.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));
    }

    private void createUsersWithRawIndex() {
        schema.create("users", t -> {
            t.id();
            t.string("name");
            t.string("email");
            t.rawIndex("lower(email)", RAW_INDEX);
        });
    }

    private Optional<String> indexSql(String name) {
        return db.scalar("select sql from sqlite_master where type = 'index' and name = ?", name)
                .map(Object::toString);
    }

    private long count(String sql) {
        return ((Number) db.scalar(sql).orElseThrow()).longValue();
    }

    @Test
    void temporaryTableLivesOnItsConnection() {
        List<LoggedQuery> sql = schema.pretend(s -> s.create("scratch", t -> {
            t.id();
            t.temporary();
        }));
        assertTrue(sql.get(0).sql().startsWith("create temporary table \"scratch\""), sql.get(0).sql());

        db.transaction(tx -> {
            Schema inTransaction = SQLiteSchema.of(tx);
            inTransaction.create("scratch", t -> {
                t.id();
                t.string("label");
                t.temporary();
            });
            tx.insert("insert into scratch (label) values ('a')");
            assertEquals(1L, ((Number) tx.scalar("select count(*) from scratch").orElseThrow()).longValue());
            assertEquals(1L, ((Number) tx.scalar(
                    "select count(*) from sqlite_temp_master where name = 'scratch'").orElseThrow()).longValue());
            return null;
        });

        Connection fresh = Connection.builder().name("fresh").dataSource(dataSource).build();
        assertEquals(0L, ((Number) fresh.scalar(
                "select count(*) from sqlite_master where name = 'scratch'").orElseThrow()).longValue());
    }

    @Test
    void temporaryTableTakesPlainAdditions() {
        db.transaction(tx -> {
            Schema inTransaction = SQLiteSchema.of(tx);
            inTransaction.create("scratch", t -> {
                t.id();
                t.string("label");
                t.temporary();
            });

            inTransaction.table("scratch", t -> {
                t.string("extra").nullable();
                t.index(List.of("label"), "scratch_label_index");
            });

            assertTrue(inTransaction.hasColumn("scratch", "extra"));
            assertEquals(1L, ((Number) tx.scalar("select count(*) from sqlite_temp_master "
                    + "where type = 'index' and name = 'scratch_label_index'").orElseThrow()).longValue());
            return null;
        });
    }

    @Test
    void changingTemporaryTableNeedingRebuildRefused() {
        db.transaction(tx -> {
            Schema inTransaction = SQLiteSchema.of(tx);
            Schema oldInTransaction = Schema.of(tx, new SQLiteSchemaGrammar("3.22.0"));
            inTransaction.create("scratch", t -> {
                t.id();
                t.string("label");
                t.temporary();
            });

            IllegalStateException rebuilt = assertThrows(IllegalStateException.class,
                    () -> inTransaction.table("scratch", t -> t.string("label", 100).change()));
            IllegalStateException dropped = assertThrows(IllegalStateException.class,
                    () -> oldInTransaction.table("scratch", t -> t.dropColumn("label")));

            assertEquals("Changing a temporary table is not supported on SQLite", rebuilt.getMessage());
            assertEquals("Changing a temporary table is not supported on SQLite", dropped.getMessage());
            return null;
        });
    }

    @Test
    void rawIndex() {
        List<LoggedQuery> sql = schema.pretend(s -> s.create("users", t -> {
            t.id();
            t.string("email");
            t.rawIndex("lower(email)", RAW_INDEX);
        }));
        assertTrue(sql.stream().anyMatch(q -> q.sql().equals(
                "create index \"users_email_lower\" on \"users\" (lower(email))")), sql.toString());

        createUsersWithRawIndex();
        assertTrue(indexSql(RAW_INDEX).isPresent());

        schema.table("users", t -> t.dropIndex(RAW_INDEX));

        assertFalse(indexSql(RAW_INDEX).isPresent());
    }

    @Test
    void tableCommentIsSkipped() {
        List<LoggedQuery> created = schema.pretend(s -> s.create("notes", t -> {
            t.id();
            t.comment("about notes");
        }));
        assertEquals(1, created.size(), created.toString());
        assertTrue(created.get(0).sql().startsWith("create table"), created.get(0).sql());

        schema.create("notes", t -> t.id());
        List<LoggedQuery> changed = schema.pretend(s -> s.table("notes", t -> t.comment("changed")));
        assertEquals(0, changed.size(), changed.toString());
        schema.table("notes", t -> t.comment("changed"));
    }

    @Test
    void foreignIdForConstrained() {
        schema.create("members", t -> t.bigInteger("member_key").primary());

        schema.create("posts", t -> {
            t.id();
            t.foreignIdFor(new Source("owner_ref", "members", "member_key")).constrained();
        });

        ForeignKeyInfo key = schema.foreignKeys("posts").get(0);
        assertEquals(List.of("owner_ref"), key.columns());
        assertEquals("members", key.foreignTable());
        assertEquals(List.of("member_key"), key.foreignColumns());
    }

    @Test
    void foreignIdForConstrainedOnAPrefixedConnection() {
        Connection prefixed = Connection.builder().name("p").tablePrefix("app_").dataSource(dataSource).build();
        Schema s = SQLiteSchema.of(prefixed);
        s.create("members", t -> t.bigInteger("member_key").primary());

        s.create("posts", t -> {
            t.id();
            t.foreignIdFor(new Source("owner_ref", "members", "member_key")).constrained();
        });

        ForeignKeyInfo key = s.foreignKeys("posts").get(0);
        assertEquals("app_members", key.foreignTable());
        assertEquals(List.of("member_key"), key.foreignColumns());
    }

    @Test
    void rebuildKeepsRawIndex() {
        createUsersWithRawIndex();
        db.insert("insert into users (name, email) values ('a', 'A@X.COM')");
        String before = indexSql(RAW_INDEX).orElseThrow();

        schema.table("users", t -> t.string("name", 100).change());

        assertEquals(before, indexSql(RAW_INDEX).orElseThrow());
        assertEquals(1L, count("select count(*) from users"));
        assertEquals(1L, count("select count(*) from users where lower(email) = 'a@x.com'"));
        schema.table("users", t -> t.dropIndex(RAW_INDEX));
        assertFalse(indexSql(RAW_INDEX).isPresent());
    }

    @Test
    void rebuildKeepsUniqueRawIndex() {
        createUsersWithRawIndex();
        schema.table("users", t -> t.dropIndex(RAW_INDEX));
        db.statement("create unique index users_email_unique_lower on users (lower(email))");
        String before = indexSql("users_email_unique_lower").orElseThrow();

        schema.table("users", t -> t.string("name", 100).change());

        assertEquals(before, indexSql("users_email_unique_lower").orElseThrow());
    }

    @Test
    void rebuildKeepsPartialRawIndex() {
        schema.create("users", t -> {
            t.id();
            t.string("name");
            t.string("email").nullable();
            t.rawIndex("lower(email)", "users_email_live_lower").whereNotNull("email");
        });
        String before = indexSql("users_email_live_lower").orElseThrow();

        schema.table("users", t -> t.string("name", 100).change());

        assertEquals(before, indexSql("users_email_live_lower").orElseThrow());
        assertTrue(before.toLowerCase(java.util.Locale.ROOT).contains("where"), before);
    }

    @Test
    void rawIndexDroppedDuringRebuild() {
        createUsersWithRawIndex();

        schema.table("users", t -> {
            t.dropIndex(RAW_INDEX);
            t.string("name", 100).change();
        });

        assertFalse(indexSql(RAW_INDEX).isPresent());
        schema.create("people", t -> {
            t.id();
            t.string("name");
            t.string("email");
        });

        schema.table("people", t -> {
            t.rawIndex("lower(email)", "people_email_lower");
            t.string("name", 100).change();
        });

        assertEquals("create index \"people_email_lower\" on \"people\" (lower(email))",
                indexSql("people_email_lower").orElseThrow().toLowerCase(java.util.Locale.ROOT));
        assertEquals(1L, count("select count(*) from sqlite_master where type = 'index' and name = 'people_email_lower'"));
    }

    @Test
    void rebuildRefusesColumnInRawIndex() {
        createUsersWithRawIndex();

        UnsupportedOperationException dropped = assertThrows(UnsupportedOperationException.class,
                () -> old.table("users", t -> t.dropColumn("email")));
        UnsupportedOperationException renamed = assertThrows(UnsupportedOperationException.class,
                () -> old.table("users", t -> t.renameColumn("email", "mail")));

        assertTrue(dropped.getMessage().contains(RAW_INDEX), dropped.getMessage());
        assertTrue(renamed.getMessage().contains(RAW_INDEX), renamed.getMessage());
        assertTrue(indexSql(RAW_INDEX).isPresent());
    }

    @Test
    void renameRawIndexRefused() {
        createUsersWithRawIndex();

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> schema.table("users", t -> t.renameIndex(RAW_INDEX, "users_mail_lower")));

        assertTrue(error.getMessage().contains("expression"), error.getMessage());
        assertTrue(indexSql(RAW_INDEX).isPresent());
    }
}

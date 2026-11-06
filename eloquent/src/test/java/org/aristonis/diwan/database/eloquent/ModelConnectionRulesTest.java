package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.sqlite.SQLiteConnector;
import org.junit.jupiter.api.Test;

/** Models on a second named connection, behind a table prefix, and without a primary key. */
class ModelConnectionRulesTest {

    /** A contact kept on the crm connection. */
    static final class Contact extends Model {
        static final ModelQuery<Contact> QUERY = ModelQuery.of(Contact.class, Contact::new);

        @Override
        protected String connectionName() {
            return "crm";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** An account whose table is reached through a connection prefix. */
    static final class PrefixedAccount extends Model {
        static final ModelQuery<PrefixedAccount> QUERY = ModelQuery.of(PrefixedAccount.class, PrefixedAccount::new);

        @Override
        protected String table() {
            return "accounts";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A model that turns its primary key off. */
    static final class Keyless extends Model {
        @Override
        protected String primaryKey() {
            return null;
        }
    }

    @Test
    void updateOrCreateWritesToTheConnectionTheModelNames() {
        DatabaseConfig config = DatabaseConfig.builder().defaultConnection("main")
                .connection(ConnectionConfig.builder("main").driver("sqlite").database(":memory:").build())
                .connection(ConnectionConfig.builder("crm").driver("sqlite").database(":memory:").build())
                .build();
        DatabaseManager manager = DatabaseManager.builder().config(config).connector(new SQLiteConnector()).build();
        try {
            manager.connection("crm").statement(
                    "create table contacts (id integer primary key autoincrement, email text, name text)");
            ModelQuery<Contact> contacts = Contact.QUERY.using(ConnectionResolver.of(manager));

            Contact created = contacts.updateOrCreate(row("email", "a@x"), row("name", "Ann"));
            Contact updated = contacts.updateOrCreate(row("email", "a@x"), row("name", "Anna"));

            assertTrue(created.wasRecentlyCreated());
            assertFalse(updated.wasRecentlyCreated());
            assertSame(manager.query("crm"), created.getQueryFactory());
            assertEquals(1L, manager.query("crm").table("contacts").count());
            assertEquals("Anna", manager.query("crm").table("contacts").value("name").get());
        } finally {
            manager.close();
        }
    }

    @Test
    void modelsReadAndWriteThroughAConnectionTablePrefix() throws Exception {
        try (SqliteModelDb database = SqliteModelDb.openWithPrefix("pre_")) {
            database.sql("create table pre_accounts (id integer primary key autoincrement, name text)");
            ModelQuery<PrefixedAccount> accounts = PrefixedAccount.QUERY.on(database.db);

            PrefixedAccount created = accounts.create(row("name", "Ann"));
            PrefixedAccount found = accounts.findOrFail(created.getKey());
            found.setAttribute("name", "Bob");
            found.save();

            assertTrue(accounts.query().toSql().contains("\"pre_accounts\""), accounts.query().toSql());
            assertEquals("Bob", database.scalar("select name from pre_accounts where id = 1"));
            assertEquals(1L, accounts.where("name", "Bob").count());
            assertTrue(found.delete());
            assertEquals(0L, accounts.query().count());
        }
    }

    @Test
    void aModelWithoutAPrimaryKeyIsNotIncrementing() {
        Keyless model = new Keyless();

        assertNull(model.getKeyName());
        assertFalse(model.getIncrementing());
    }
}

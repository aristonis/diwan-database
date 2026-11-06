/**
 * Diwan Database core: the connection, its driver interface, the JDBC adapter, migrations, the
 * schema builder and the query builder.
 *
 * <p>The {@code internal} packages are not exported; they are not part of the API.
 */
module org.aristonis.diwan.database {
    requires transitive java.sql;

    exports org.aristonis.diwan.database;
    exports org.aristonis.diwan.database.config;
    exports org.aristonis.diwan.database.driver;
    exports org.aristonis.diwan.database.driver.sqlite;
    exports org.aristonis.diwan.database.event;
    exports org.aristonis.diwan.database.exception;
    exports org.aristonis.diwan.database.jdbc;
    exports org.aristonis.diwan.database.manager;
    exports org.aristonis.diwan.database.migrations;
    exports org.aristonis.diwan.database.migrations.event;
    exports org.aristonis.diwan.database.query;
    exports org.aristonis.diwan.database.schema;
    exports org.aristonis.diwan.database.schema.grammar;
    exports org.aristonis.diwan.database.seeding;
    exports org.aristonis.diwan.database.support;
    exports org.aristonis.diwan.database.values;
}

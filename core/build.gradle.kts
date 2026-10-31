plugins {
    id("diwan.java-library")
}

description = "Diwan Database core: the connection, its driver interface, the JDBC adapter, migrations and the schema builder."

dependencies {
    // Migrator behaviour is tested against real SQLite DDL; core's main code never sees the module.
    testImplementation(project(":sqlite"))
}

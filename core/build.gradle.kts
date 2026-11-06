plugins {
    id("diwan.java-library")
}

description = "Diwan Database core: connections and transactions, the JDBC adapter, the query and schema builders, and migrations."

dependencies {
    // Migrator behaviour is tested against real SQLite DDL; core's main code never sees the module.
    testImplementation(project(":sqlite"))
}

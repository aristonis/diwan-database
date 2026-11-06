plugins {
    id("diwan.java-library")
}

description = "Diwan Database SQLite: the SQLite query and schema grammars and the connector."

dependencies {
    api(project(":core"))

    testImplementation("com.zaxxer:HikariCP:5.1.0")
}

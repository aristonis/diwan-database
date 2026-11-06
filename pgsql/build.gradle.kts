plugins {
    id("diwan.java-library")
}

description = "Diwan Database PostgreSQL: the PostgreSQL query and schema grammars, the connector and the migration lock."

dependencies {
    api(project(":core"))
    testImplementation("org.postgresql:postgresql:42.7.5")
}

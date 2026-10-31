plugins {
    id("diwan.java-library")
}

description = "Diwan Database PostgreSQL: the schema grammar and migration lock for PostgreSQL."

dependencies {
    api(project(":core"))
    testImplementation("org.postgresql:postgresql:42.7.5")
}

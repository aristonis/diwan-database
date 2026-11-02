plugins {
    id("diwan.java-library")
}

description = "Diwan Database Eloquent: models over the query builder, in the style of Laravel's Eloquent."

dependencies {
    api(project(":core"))
    // Models are tested against real SQLite and PostgreSQL; main code never sees either module.
    testImplementation(project(":sqlite"))
    testImplementation(project(":pgsql"))
    testImplementation("org.postgresql:postgresql:42.7.5")
}

// The internal bridges are not API; keep them out of the published Javadoc.
tasks.javadoc {
    exclude("**/internal/**")
}

plugins {
    id("diwan.java-library")
}

description = "Diwan Database Eloquent: models, casts and relations over the query builder, in the style of Laravel's Eloquent."

dependencies {
    api(project(":core"))
    // Models are tested against real SQLite and PostgreSQL; main code never sees either module.
    testImplementation(project(":sqlite"))
    testImplementation(project(":pgsql"))
    testImplementation("org.postgresql:postgresql:42.7.5")
}

// Javadoc leaves the internal bridges out of its pages and checks, so doclint at compile time is
// their only docs check.
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.addAll(
        listOf(
            "-Xdoclint:all/package",
            "-Xdoclint/package:org.aristonis.diwan.database.eloquent.internal.*",
        ),
    )
}

// ClassValue is missing below Android API 34; ClassCache reaches it only after probing for it at runtime.
animalsniffer {
    ignore = listOf("java.lang.ClassValue")
}

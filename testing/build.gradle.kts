plugins {
    id("diwan.java-library")
}

description = "Diwan Database Testing: a JUnit 5 extension that resets the database between tests, and database assertions."

dependencies {
    api(project(":core"))
    // The extension and the assertions need JUnit and, for model assertions, the eloquent module only
    // when the application already uses them; neither is forced on a consumer.
    compileOnly(platform("org.junit:junit-bom:6.1.3"))
    compileOnly("org.junit.jupiter:junit-jupiter-api")
    compileOnly(project(":eloquent"))

    testImplementation(project(":sqlite"))
    testImplementation(project(":pgsql"))
    testImplementation(project(":eloquent"))
    testImplementation("org.postgresql:postgresql:42.7.5")
    // Runs fixture test classes through the engine, to check behavior that spans test classes.
    testImplementation("org.junit.platform:junit-platform-testkit")
}

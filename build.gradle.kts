// Combined API reference for every module, built in classpath mode so the packages of the
// modules are grouped on one site. Output: build/api (see scripts/publish-api.sh).
val apiModules = listOf("core", "sqlite", "pgsql", "eloquent", "android", "testing")
val apiBase = "org.aristonis.diwan.database"

// A source path holding a module-info.java switches javac to module mode, which hides the class
// path; the sources are merged without it so the build stays in classpath mode.
val apiSources = tasks.register<Sync>("apiSources") {
    into(layout.buildDirectory.dir("api-src"))
    exclude("module-info.java")
    apiModules.mapNotNull { findProject(":$it") }.forEach { from(it.layout.projectDirectory.dir("src/main/java")) }
}

tasks.register<Javadoc>("apiDocs") {
    description = "Builds the combined API reference into build/api."
    group = "documentation"

    if (findProject(":android") == null) {
        doFirst {
            throw GradleException(
                "The android module is missing, so the API site would be incomplete. " +
                    "Set ANDROID_HOME to an Android SDK with a platform 28 or later and run again."
            )
        }
    }

    val modules = apiModules.mapNotNull { findProject(":$it") }
    val mainSets = modules.map { it.extensions.getByType<SourceSetContainer>()["main"] }
    modules.forEach { dependsOn(it.tasks.named("classes")) }
    dependsOn(apiSources)
    val sourceDir = layout.buildDirectory.dir("api-src").get().asFile

    setSource(fileTree(sourceDir))
    exclude("**/internal/**")
    classpath = files(mainSets.map { it.compileClasspath }) + files(mainSets.map { it.output })
    modularity.inferModulePath = false
    setDestinationDir(layout.buildDirectory.dir("api").get().asFile)

    val toolchains = project(":core").extensions.getByType<JavaToolchainService>()
    javadocTool = toolchains.javadocToolFor { languageVersion = JavaLanguageVersion.of(21) }

    val title = "Diwan Database ${project.version} API"
    (options as StandardJavadocDocletOptions).apply {
        addPathOption("sourcepath").value = listOf(sourceDir)
        encoding = "UTF-8"
        windowTitle = title
        docTitle = title
        addStringOption("-release", "17")
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Xdoclint/package:-$apiBase.internal,-$apiBase.query.internal,"
            + "-$apiBase.schema.internal,-$apiBase.migrations.internal,-$apiBase.eloquent.internal", true)
        addBooleanOption("Werror", true)
        tags("apiNote:a:API Note:", "implSpec:a:Implementation Requirements:", "implNote:a:Implementation Note:")
        addBooleanOption("use", true)
        links("https://docs.oracle.com/en/java/javase/17/docs/api/")
        overview = file("src/api/overview.html").absolutePath
        addStringOption("-add-stylesheet", file("src/api/diwan.css").absolutePath)
        addStringOption("-add-script", file("src/api/switcher.js").absolutePath)
        // Packages of the other modules share the org.aristonis.diwan.database prefix, so core is listed explicitly.
        val core = listOf("", ".config", ".driver*", ".event", ".exception", ".jdbc*", ".manager",
            ".migrations*", ".query*", ".schema*", ".seeding", ".support*", ".values")
            .joinToString(":") { "$apiBase$it" }
        group("Core", core)
        group("SQLite", "$apiBase.sqlite*")
        group("PostgreSQL", "$apiBase.pgsql*")
        group("Eloquent", "$apiBase.eloquent*")
        group("Android", "$apiBase.android*")
        group("Testing", "$apiBase.testing*")
    }
}

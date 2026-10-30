plugins {
    `java-library`
}

// The core module is published as photisma-database; each driver module as
// photisma-database-<driver>, e.g. photisma-database-sqlite.
base {
    archivesName = if (project.name == "core") "photisma-database" else "photisma-database-${project.name}"
}

// The library supports Java 17 and newer. A few classes may have a Java 21 copy in
// src/main/java21; the JVM picks it on 21+ (multi-release JAR).
val baseline = 17
val overlay = 21
val testedOn = listOf(17, 21, 25)

repositories {
    mavenCentral()
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(overlay) }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val java21: SourceSet = sourceSets.create("java$overlay") {
    java.srcDir("src/main/java$overlay")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}

tasks.withType<JavaCompile>().matching { it.name != java21.compileJavaTaskName }.configureEach {
    options.release = baseline
}

tasks.named<JavaCompile>(java21.compileJavaTaskName) {
    options.release = overlay
}

tasks.jar {
    into("META-INF/versions/$overlay") { from(java21.output) }
    manifest { attributes("Multi-Release" to "true") }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// One test task per supported JDK; `check` runs them all. Tests run against the JAR so
// each JDK loads the classes it would load in production.
val toolchains = extensions.getByType<JavaToolchainService>()
val perJdk = testedOn.map { version ->
    tasks.register<Test>("testOn$version") {
        description = "Runs the tests on JDK $version."
        group = "verification"
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = files(tasks.jar) + sourceSets.test.get().output +
            configurations.testRuntimeClasspath.get()
        javaLauncher = toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(version) }
    }
}

tasks.test {
    enabled = false
}

tasks.check {
    dependsOn(perJdk)
}

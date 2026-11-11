plugins {
    `java-library`
    jacoco
    id("ru.vyarus.animalsniffer")
    signing
    id("com.vanniktech.maven.publish")
}

// The core module is published as diwan-database; each driver module as
// diwan-database-<driver>, e.g. diwan-database-sqlite.
base {
    archivesName = if (project.name == "core") "diwan-database" else "diwan-database-${project.name}"
}

// One plain Java 17 JAR. Android never loads a multi-release overlay and older Android build
// tools reject one, so newer JDKs get the same bytecode and are only tested against it.
val baseline = 17
val testedOn = listOf(17, 21, 25)
val minimumLineCoverage = "0.80".toBigDecimal()

repositories {
    mavenCentral()
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.xerial:sqlite-jdbc:3.54.1.0")

    // Android 9 (API 28) lacks JDK methods such as List.of and Stream.toList; the check fails
    // the build on any of them in main code.
    signature("net.sf.androidscents.signature:android-api-level-28:9_r6@signature")
}

animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}

tasks.withType<JavaCompile>().configureEach {
    options.release = baseline
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// Internal packages are not API: their pages are left out, but Javadoc still reads their sources
// through the source path, because API classes import them.
tasks.javadoc {
    exclude("**/internal/**")
    (options as StandardJavadocDocletOptions).addPathOption("sourcepath").value = sourceSets.main.get().java.srcDirs.toList()
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("-release", baseline.toString())
        addBooleanOption("Xdoclint:all", true)
        // The doc check skips the internal packages read through the source path.
        addBooleanOption("Xdoclint/package:-org.aristonis.diwan.database.internal,"
            + "-org.aristonis.diwan.database.query.internal,-org.aristonis.diwan.database.schema.internal,"
            + "-org.aristonis.diwan.database.migrations.internal,-org.aristonis.diwan.database.eloquent.internal", true)
        addBooleanOption("Werror", true)
        // The JDK's own contract tags; the documentation standard relies on all three.
        tags("apiNote:a:API Note:", "implSpec:a:Implementation Requirements:", "implNote:a:Implementation Note:")
    }
}

// One test task per supported JDK; `check` runs them all. Tests run against the JAR so
// each JDK loads the classes it would load in production.
val toolchains = extensions.getByType<JavaToolchainService>()
val perJdk = testedOn.map { version ->
    tasks.register<Test>("testOn$version") {
        description = "Runs the tests on JDK $version."
        group = "verification"
        useJUnitPlatform()
        // Command-line -Ddiwan.* switches (for example -Ddiwan.benchmark=true) reach the forked test JVM.
        providers.systemPropertiesPrefixedBy("diwan.").get().forEach { (key, value) -> systemProperty(key, value) }
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = files(tasks.jar) + sourceSets.test.get().output +
            configurations.testRuntimeClasspath.get()
        javaLauncher = toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(version) }
    }
}

tasks.test {
    enabled = false
}

// Coverage is measured on the baseline JDK run; the other JDKs run the same bytecode.
val coverageRun = perJdk.first()

tasks.jacocoTestReport {
    dependsOn(coverageRun)
    executionData.setFrom(coverageRun.map { it.extensions.getByType<JacocoTaskExtension>().destinationFile!! })
    reports { xml.required = true }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(coverageRun)
    executionData.setFrom(coverageRun.map { it.extensions.getByType<JacocoTaskExtension>().destinationFile!! })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = minimumLineCoverage
            }
            // Zero lines scores as a pass, so an untested module would slip through without this.
            limit {
                counter = "CLASS"
                value = "COVEREDCOUNT"
                minimum = "1".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(perJdk, tasks.jacocoTestCoverageVerification, tasks.javadoc)
}

// Maven Central through the Central Portal. The upload stops at "validated"; a person publishes it
// in the Portal, because a released version can never be changed or removed.
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    // Signs with the local gpg key named by signing.gnupg.keyName; without it nothing is signed,
    // so a local dry run works on any machine.
    if (providers.gradleProperty("signing.gnupg.keyName").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), base.archivesName.get(), version.toString())
    pom {
        name = base.archivesName.get()
        description = project.description
        inceptionYear = "2026"
        url = "https://github.com/aristonis/diwan-database"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "aristonis"
                name = "Aristonis"
                email = "aristonis@outlook.com"
                url = "https://aristonis.org"
            }
        }
        scm {
            url = "https://github.com/aristonis/diwan-database"
            connection = "scm:git:https://github.com/aristonis/diwan-database.git"
            developerConnection = "scm:git:ssh://git@github.com/aristonis/diwan-database.git"
        }
        issueManagement {
            system = "GitHub"
            url = "https://github.com/aristonis/diwan-database/issues"
        }
    }
}

signing {
    useGpgCmd()
}

// A local copy of what Maven Central receives, for checking a release before the upload.
publishing {
    repositories {
        maven {
            name = "localStaging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
}

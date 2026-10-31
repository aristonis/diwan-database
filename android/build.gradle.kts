import java.util.zip.ZipFile

plugins {
    id("diwan.java-library")
}

description = "Diwan Database Android: a driver over Android's SQLiteDatabase."

// Robolectric's androidx.test parts are published on Google's repository, for tests only.
repositories {
    google()
}

// The lowest installed platform at or above API 28; Animal Sniffer keeps main code on API 28.
val sdk = file(System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
val androidJar = (sdk.resolve("platforms").listFiles() ?: emptyArray())
    // Names such as android-37.0 count by their major version.
    .mapNotNull { dir ->
        dir.name.removePrefix("android-").substringBefore('.').toIntOrNull()?.let { it to dir.resolve("android.jar") }
    }
    .filter { (level, jar) -> level >= 28 && jar.isFile }
    .minByOrNull { it.first }?.second
    ?: error("No Android platform 28 or later under $sdk/platforms")

dependencies {
    api(project(":core"))
    compileOnly(files(androidJar))
    // Test discovery loads the classes before Robolectric does; Robolectric still runs its own Android.
    testCompileOnly(files(androidJar))
    testRuntimeOnly(files(androidJar))
    testImplementation(project(":sqlite"))
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

// Robolectric pulls androidx.test as Android libraries (AAR); a plain Java build reads only the
// classes.jar inside them.
abstract class AarClasses : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val aar: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val file = aar.get().asFile
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("classes.jar") ?: return
            val out = outputs.file(file.nameWithoutExtension + ".jar")
            zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
}

val artifactType = Attribute.of("artifactType", String::class.java)

dependencies {
    registerTransform(AarClasses::class) {
        from.attribute(artifactType, "aar")
        to.attribute(artifactType, "jar")
    }
}

configurations.named("testCompileClasspath") { attributes.attribute(artifactType, "jar") }
configurations.named("testRuntimeClasspath") { attributes.attribute(artifactType, "jar") }

// Robolectric loads classes through its own class loader; JaCoCo skips such classes unless told.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

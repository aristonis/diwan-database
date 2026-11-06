plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("ru.vyarus:gradle-animalsniffer-plugin:2.0.1")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.37.0")
}

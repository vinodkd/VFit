import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // ./gradlew :model:test -Pgolden.extra=/path/to/export_dir  also checks a private export
    systemProperty("golden.extra", providers.gradleProperty("golden.extra").getOrElse(""))
}

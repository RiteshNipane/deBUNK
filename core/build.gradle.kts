plugins { id("org.jetbrains.kotlin.jvm") }

// No jvmToolchain(): that forces Gradle to find an exact JDK and fails where only another version is installed.
// Instead compile with whatever JDK runs Gradle, targeting Java 17 bytecode (same as :app).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

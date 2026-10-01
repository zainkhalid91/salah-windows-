plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin { jvmToolchain(21) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
    // Independent reference implementation from the same authors, used only to cross-check our port.
    testImplementation("com.batoulapps.adhan:adhan:1.2.1")
}

tasks.test { useJUnitPlatform() }

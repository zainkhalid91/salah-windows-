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

// The app version, from gradle.properties or CI (-PsalahVersion=1.5.42), compiled into SalahInfo.VERSION.
val salahVersion: String = providers.gradleProperty("salahVersion").getOrElse("1.5.0")
val generateVersion by tasks.registering {
    val version = salahVersion
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val f = out.get().file("salah/core/BuildVersion.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package salah.core\n\ninternal const val BUILD_VERSION = \"$version\"\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateVersion) }

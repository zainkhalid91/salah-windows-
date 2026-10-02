import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    // The CLI ships inside the installer (salah.cmd runs it on the bundled runtime).
    implementation(project(":cli"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Dispatchers.Main on the Swing event thread. Compose doesn't bring it in on its own; without it
    // the app crashes at startup ("Module with the Main dispatcher is missing").
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    // Dark title bar (DWM) and reading Windows' app mode without spawning processes.
    implementation("net.java.dev.jna:jna-platform:5.15.0")
}

val appVersion = "1.3.0"

compose.desktop {
    application {
        mainClass = "salah.app.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xms32m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Salah"
            packageVersion = appVersion
            description = "Prayer times, countdowns and reminders"
            vendor = "Salah"
            copyright = "Salah contributors"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("packaging/resources"))
            // java.net.http for city search and updates; the rest for TLS, prefs and the tray.
            modules("jdk.unsupported", "java.net.http", "java.prefs", "jdk.crypto.ec", "jdk.accessibility", "java.naming", "jdk.localedata")

            windows {
                iconFile.set(project.file("packaging/salah.ico"))
                menuGroup = "Salah"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Stable across versions so the MSI upgrades in place.
                upgradeUuid = "6F0B6C8E-3C55-4C5A-9B8E-5A1A2D7E4C31"
            }
        }

        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

// Compose's jlink step strips java.exe from the bundled runtime; the `salah` command (salah.cmd)
// needs it. Copy it back from the same JDK that built the runtime, so versions always match.
tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    doLast {
        if (!System.getProperty("os.name").lowercase().startsWith("windows")) return@doLast
        val runtimeBin = layout.buildDirectory.dir("compose/tmp/main/runtime/bin").get().asFile.apply { mkdirs() }
        val jdkBin = File(System.getProperty("java.home"), "bin")
        for (name in listOf("java.exe", "jli.dll")) {
            val target = runtimeBin.resolve(name)
            if (!target.exists()) jdkBin.resolve(name).copyTo(target)
        }
        check(runtimeBin.resolve("java.exe").exists()) { "java.exe missing from bundled runtime" }
    }
}

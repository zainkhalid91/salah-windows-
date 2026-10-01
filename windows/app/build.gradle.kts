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
}

val appVersion = "1.2.0"

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
            modules("java.net.http", "java.prefs", "jdk.crypto.ec", "jdk.accessibility", "java.naming", "jdk.localedata")

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

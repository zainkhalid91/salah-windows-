plugins { kotlin("jvm"); kotlin("plugin.compose"); id("org.jetbrains.compose") }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}
compose.desktop { application { mainClass = "salah.app.MainKt" } }

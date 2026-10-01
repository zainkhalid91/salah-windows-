plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("salah.cli.MainKt")
    applicationName = "salah"
}

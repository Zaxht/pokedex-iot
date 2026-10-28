plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin { jvmToolchain(17) }
application { mainClass.set("cl.pokedex.server.MainKt") }
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

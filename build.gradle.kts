import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.github.johnrengelman.shadow").version("8.1.1")
}

version = "2026.07.09.24"

repositories {
    mavenCentral()
    maven("https://repo.alessiodp.com/releases/")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":spigot"))
    implementation(project(":bungeecord"))
    implementation(project(":velocity"))
}

tasks {
    shadowJar {
        archiveVersion.set(project.version.toString())
        relocate("com.alessiodp.libby", "com.siberanka.twiantivpn.libs.com.alessiodp.libby")
        relocate("com.google.gson", "com.siberanka.twiantivpn.libs.com.google.gson")
        relocate("org.bstats", "com.siberanka.twiantivpn.libs.org.bstats")
    }

}

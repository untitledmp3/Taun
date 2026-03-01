plugins {
    id("fabric-loom") version "1.13.6"
    id("maven-publish")
    java
}

version = "2.0.0"
group = "com.Taun"

base {
    archivesName.set("Taun+++")
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.terraformersmc.com/releases") }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.4:v2")
    modImplementation("net.fabricmc:fabric-loader:0.18.1")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.2+1.21.11")

    // ModMenu — optional, provides the config screen button in the mods list
    modCompileOnly("com.terraformersmc:modmenu:13.0.0")

    // Cobalt GUI support (optional — mod works without it)
    // Build Cobalt from https://github.com/CobaltScripts/Cobalt, copy jar to libs/cobalt.jar
    compileOnly(files("libs/cobalt.jar"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

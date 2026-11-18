plugins {
    java
}

group = "dev.boomerang"
version = "1.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// Ресурспак из папки resourcepack упаковывается в zip и кладётся внутрь jar.
val packZip = tasks.register<Zip>("packZip") {
    from("resourcepack")
    archiveFileName.set("BoomerangPack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/pack"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processResources {
    from(packZip)
}

tasks.jar {
    archiveFileName.set("Boomerang.jar")
}

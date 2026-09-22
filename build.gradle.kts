plugins {
    id("net.neoforged.moddev") version "2.0.107"
}

val minecraftVersion = "1.21.1"
val neoForgeVersion = "21.1.197"
val parchmentVersion = "2024.11.17"
val parchmentMinecraftVersion = "1.21.1"
val immersiveAircraftVersion = "1.4.6+1.21.1+neoforge"

val modId = "immersive_aircraft_cruise"
val modName = "Immersive_Aircraft_Modernization"
val modVersion = "1.0.0"
val modGroupId = "com.g1739.immersiveaircraftcruise"
val modAuthors = "交错次元"
val modLicense = "All Rights Reserved"
val modDescription = "Adds a configurable overclocked cruise guidance module for Immersive Aircraft, with route preloading, auto navigation, dual HUD time estimates, and landing modes."

val localImmersiveAircraftJar = file("../local-deps/immersive_aircraft-1.4.6+1.21.1-neoforge.jar")
val localImmersiveAircraftEnabled = providers.gradleProperty("immersive_aircraft_use_local")
    .map { it.toBoolean() }
    .orElse(true)
    .get()
val useLocalImmersiveAircraft = localImmersiveAircraftEnabled && localImmersiveAircraftJar.isFile

logger.lifecycle(
    "Immersive Aircraft dependency: " + if (useLocalImmersiveAircraft) {
        "local file $localImmersiveAircraftJar"
    } else {
        "remote net.conczin:immersive_aircraft:$immersiveAircraftVersion"
    }
)

group = modGroupId
version = modVersion

base {
    archivesName.set("$modName-NeoForge-$minecraftVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://maven.conczin.net/Artifacts")
    maven("https://maven.blamejared.com/")
}

neoForge {
    version = neoForgeVersion

    parchment {
        minecraftVersion.set(parchmentMinecraftVersion)
        mappingsVersion.set(parchmentVersion)
    }

    runs {
        configureEach {
            jvmArguments.addAll("-XX:+IgnoreUnrecognizedVMOptions", "-XX:+AllowEnhancedClassRedefinition", "-ea")
        }
        register("client") {
            client()
            gameDirectory = file("run/client")
        }
        register("server") {
            server()
            gameDirectory = file("run/server")
            programArgument("--nogui")
        }
    }

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    if (useLocalImmersiveAircraft) {
        compileOnly(files(localImmersiveAircraftJar))
        runtimeOnly(files(localImmersiveAircraftJar))
    } else {
        compileOnly("net.conczin:immersive_aircraft:$immersiveAircraftVersion")
        runtimeOnly("net.conczin:immersive_aircraft:$immersiveAircraftVersion")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val replaceProperties = mapOf(
        "minecraft_version" to minecraftVersion,
        "neoforge_version" to neoForgeVersion,
        "immersive_aircraft_version" to immersiveAircraftVersion,
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_license" to modLicense,
        "mod_version" to modVersion,
        "mod_authors" to modAuthors,
        "mod_description" to modDescription
    )
    inputs.properties(replaceProperties)

    filesMatching(listOf("META-INF/neoforge.mods.toml", "pack.mcmeta")) {
        expand(replaceProperties)
    }

    from(rootDir) {
        include("LICENSE", "DISCLAIMER.md")
        into("META-INF")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Specification-Title" to modId,
            "Specification-Vendor" to modAuthors,
            "Specification-Version" to "1",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to modAuthors,
            "MixinConfigs" to "$modId.mixins.json",
            "Bundle-License" to "All Rights Reserved"
        )
    }
}

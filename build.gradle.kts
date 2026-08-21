plugins {
    id("java")
    id("application")
}

group = "com.minenova"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:5.0.0-beta.24") {
        exclude(module = "slf4j-api")
    }
    implementation("org.slf4j:slf4j-simple:2.0.9")
    implementation("com.google.code.gson:gson:2.10.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

application {
    mainClass.set("com.minenova.discord.BotMain")
}

tasks.jar {
    archiveBaseName.set("MineNova-DiscordBot")
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.minenova.discord.BotMain"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

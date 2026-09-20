plugins {
    application
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "org.iotsplab"

repositories {
    mavenCentral()
    flatDir(
        mapOf(
            "dirs" to listOf("libs")
        )
    )
}

application {
    mainClass.set("org.iotsplab.akiba.dbDaemon.DatabaseDaemon")
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("info.picocli:picocli:4.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    implementation("org.apache.logging.log4j:log4j-api:2.24.3")
    implementation("org.apache.logging.log4j:log4j-core:2.24.3")

    // Ktor server supports
    // implementation("io.netty:netty-transport-native-epoll:4.2.7.Final:linux-x86_64")

    implementation(platform("io.ktor:ktor-bom:3.1.3"))
    implementation("io.ktor:ktor-server:3.1.3")
    implementation("io.ktor:ktor-server-netty:3.1.3")
    implementation("io.ktor:ktor-server-websockets:3.1.3")
    implementation("io.ktor:ktor-serialization-jackson-jvm:3.1.3")

    // Jackson supports
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.20.1"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")

    // PostgreSQL
    implementation("org.postgresql:postgresql:42.7.8")
}

tasks.distZip {
    into("akiba_db_daemon-$version/resources") {
        from("src/main/resources/initialize_pg_instance.sh")
        from("src/main/resources/initialize_pg_local.sh")
        from("src/main/resources/database_init.sql")
        from("src/main/resources/backup_db_init.sql")
        from("src/main/resources/config.json")      // For Test
    }
}

tasks.distTar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "8g"
}

kotlin {
    jvmToolchain(21)
}
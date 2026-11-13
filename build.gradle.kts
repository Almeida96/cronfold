plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "io.github.almeida96"
version = "0.1.0"
repositories { mavenCentral() }
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

kotlin { jvmToolchain(21) }
application {
    mainClass = "io.github.almeida96.cronfold.MainKt"
    applicationDefaultJvmArgs = listOf("-Xmx512m")
}
tasks.test { useJUnitPlatform() }
dependencyLocking { lockAllConfigurations() }
sourceSets.main { resources.srcDir("examples") }
distributions {
    main {
        contents {
            from("README.md", "LICENSE")
            from("examples") { into("examples") }
            from("docs") { into("docs") }
        }
    }
}

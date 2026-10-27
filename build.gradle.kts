import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm") version "2.4.10"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "com.ticksnick"
version = "0.5.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

// Compiled against Java 21, so an API added after 21 fails here and not at a user's run time.
kotlin {
    jvmToolchain(21)
}

tasks.named<Jar>("jar") {
    // `java -cp ticksnick.jar:kotlin-stdlib.jar com.ticksnick.Cli render <folder>`
    manifest { attributes("Main-Class" to "com.ticksnick.Cli") }
}

// `./gradlew cli --args="render profiles/after"`, `--args="diff profiles/before profiles/after"`.
tasks.register<JavaExec>("cli") {
    group = "application"
    description = "render a folder of runs, or diff two: --args=\"render <folder>\" | --args=\"diff <before> <after>\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ticksnick.Cli")
}

tasks.test {
    useJUnitPlatform()
}

// `./gradlew publishToMavenCentral` uploads a signed deployment; "Publish" on
// central.sonatype.com/publishing/deployments releases it. Credentials and the signing key live in
// ~/.gradle/gradle.properties: mavenCentralUsername, mavenCentralPassword, signingInMemoryKey,
// signingInMemoryKeyPassword.
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("com.ticksnick", "ticksnick", version.toString())
    pom {
        name.set("ticksnick")
        description.set(
            "A sampling profiler for operations too short for a stack profiler to see: " +
                    "labels written to a thread-local slot, read by a sampling thread, with a " +
                    "measured bound on the error of every share."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/minogin/ticksnick")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("minogin")
                name.set("Andrey Minogin")
                url.set("https://github.com/minogin")
            }
        }
        scm {
            url.set("https://github.com/minogin/ticksnick")
            connection.set("scm:git:https://github.com/minogin/ticksnick.git")
            developerConnection.set("scm:git:ssh://git@github.com/minogin/ticksnick.git")
        }
    }
}

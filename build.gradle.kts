import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm") version "2.4.10"
    application
    // Publishes to Maven Central through the Central Portal: signing, the sources and javadoc jars and
    // the upload. JitPack was dropped: its builder kept hitting Central's rate limit. See plan.md.
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "com.ticksnick"

// 0.x on purpose. The fine tier is measured and reviewed, but the public surface has not yet had a
// release's worth of other people's use, and a 0.x says the API may still move. See README.
version = "0.3.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

// Java 21, not the JDK this happens to be developed on.
//
// The toolchain was 26, which compiled the library to class file version 70 — runnable only on a
// JDK 26 that almost nobody has. A profiler nobody can put on the classpath is not released. 21 is
// the current LTS and it is also the floor this code genuinely needs: `Thread.threadId()` arrived in
// 19, and everything else here — VarHandles, thread state, ThreadMXBean — is far older.
//
// Compiling *against* 21 rather than targeting it from a newer JDK, so that an API added after 21
// cannot slip in and fail at a user's run time instead of our compile time.
kotlin {
    jvmToolchain(21)
}

// The bench lives in this module so that `internal` reaches the sampler's counters, which is what
// lets the library keep them out of its public API. That is right for the source tree and wrong for
// the artifact: nobody depending on this wants Bench, Workload and StackCost on their classpath.
// `run` is unaffected — it builds from the classes directory, not from the jar.
tasks.named<Jar>("jar") {
    exclude("com/ticksnick/bench/**")
    // The saved-profile commands, see Cli.kt. The jar needs the Kotlin stdlib beside it, so the
    // plain form is `java -cp ticksnick.jar:kotlin-stdlib.jar com.ticksnick.Cli diff a b`; the
    // task below is the same with the classpath found for you.
    manifest { attributes("Main-Class" to "com.ticksnick.Cli") }
}

// `./gradlew :cli --args="diff before.tsp after.tsp"` and `--args="render profile.tsp"`.
tasks.register<JavaExec>("cli") {
    group = "application"
    description = "render a saved profile, or diff two: --args=\"render f.tsp\" | --args=\"diff a.tsp b.tsp\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ticksnick.Cli")
}
application {
    mainClass.set("com.ticksnick.bench.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// The library takes no dependencies at all, and the published POM has to keep saying so. The bench
// and the trials live in this repository but never in the artifact: the bench is in a package of
// its own inside this module, and every trial is a separate module.
//
// `./gradlew publishToMavenCentral` uploads a signed deployment, then "Publish" on
// central.sonatype.com/publishing/deployments releases it. The credentials and the signing key are in
// ~/.gradle/gradle.properties, never here: mavenCentralUsername, mavenCentralPassword (a Central
// Portal user token), signingInMemoryKey, signingInMemoryKeyPassword.
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    // Only where the key is: a local publish or a build from source needs no key, and Central refuses
    // an unsigned deployment anyway, so a release without one fails there, loudly.
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
tasks.named<Jar>("sourcesJar") { exclude("com/ticksnick/bench/**") }

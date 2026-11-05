import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

group = "com.makefunstuff.herdr"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("com.hierynomus:sshj:0.39.0")
    api("org.bouncycastle:bcprov-jdk18on:1.79")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Reads the QR codes herdr-push pair draws, as a phone's scanner would.
    testImplementation("com.google.zxing:core:3.5.3")
}

// The host-side watcher (host/herdr-push), the usage reader (host/herdr-usage), the logins reader
// (host/herdr-logins) and the app's builder (host/herdr-app-build) ride along as resources: the app copies them to a machine.
sourceSets.main {
    resources.srcDir("../host/herdr-push")
    resources.include("herdr-push")
}
tasks.processResources {
    from("../host/herdr-usage") { include("herdr-usage") }
    from("../host/herdr-logins") { include("herdr-logins") }
    from("../host/herdr-app-build") { include("herdr-app-build") }
}

tasks.test {
    useJUnitPlatform()
    // The interop tests run the watcher itself with python3.
    systemProperty("herdrPush", file("../host/herdr-push/herdr-push").absolutePath)
    // The usage tests run the usage reader itself with python3.
    systemProperty("herdrUsage", file("../host/herdr-usage/herdr-usage").absolutePath)
    // The logins tests run the logins reader itself with python3, against fake CLIs.
    systemProperty("herdrLogins", file("../host/herdr-logins/herdr-logins").absolutePath)
    // The app builder's tests run it with python3, against a fake checkout, Gradle and herdr-secret.
    systemProperty("herdrAppBuild", file("../host/herdr-app-build/herdr-app-build").absolutePath)
    // A failure says why in the build's own log (CI shows nothing else): its message and where.
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

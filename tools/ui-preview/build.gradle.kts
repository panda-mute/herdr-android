import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Renders the app's real Compose screens on the desktop JVM (Compose Multiplatform), with a fake
// machine behind the SSH layer, so screens can be checked without an Android SDK or an emulator.
// See README.md here and AGENTS.md.
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-nowarn", "-opt-in=androidx.compose.ui.test.ExperimentalTestApi")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.desktop.uiTestJUnit4)
    // Multiplatform builds of the androidx libraries the app uses, under the same package names.
    implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha12")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    // core/'s own dependencies (core/build.gradle.kts).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.hierynomus:sshj:0.39.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
}

// The app and core sources themselves, minus the files that only work on Android or over real SSH.
// Each has a stand-in with the same API under src/main/kotlin/standins (see README.md here).
val androidOnly = listOf(
    "com/makefunstuff/herdr/MainActivity.kt",
    "com/makefunstuff/herdr/HerdrApp.kt",
    "com/makefunstuff/herdr/data/IdentityStore.kt",
    "com/makefunstuff/herdr/ui/terminal/TerminalView.kt",
    "com/makefunstuff/herdr/ui/common/Attach.kt",
    "com/makefunstuff/herdr/ui/common/CodeScanner.kt",
    "com/makefunstuff/herdr/ui/common/Mic.kt",
    "com/makefunstuff/herdr/ui/common/Ears.kt",
    "com/makefunstuff/herdr/ui/common/Playback.kt",
    "com/makefunstuff/herdr/ui/common/SecureWindow.kt",
    "com/makefunstuff/herdr/ui/images/ImageCodec.kt",
    "com/makefunstuff/herdr/core/ssh/SshConnector.kt",
    "com/makefunstuff/herdr/push/AndroidPush.kt",
    "com/makefunstuff/herdr/push/AndroidAlerts.kt",
    "com/makefunstuff/herdr/push/AndroidLive.kt",
    "com/makefunstuff/herdr/push/WorkService.kt",
    "com/makefunstuff/herdr/share/ShareActivity.kt",
    "com/makefunstuff/herdr/share/CoordinatorTile.kt",
    "com/makefunstuff/herdr/share/Widgets.kt",
    "com/makefunstuff/herdr/share/AndroidShortcuts.kt",
    "com/makefunstuff/herdr/bubble/AndroidBubbles.kt",
    "com/makefunstuff/herdr/bubble/BubbleActivity.kt",
    "com/makefunstuff/herdr/brief/AndroidBrief.kt",
    "com/makefunstuff/herdr/brief/AndroidVoice.kt",
    "com/makefunstuff/herdr/guard/AndroidUnlock.kt",
    "com/makefunstuff/herdr/guard/ConfirmActivity.kt",
    "com/makefunstuff/herdr/voice/AndroidCoordinatorVoice.kt",
    "com/makefunstuff/herdr/voice/AndroidVoiceCalls.kt",
    "com/makefunstuff/herdr/voice/AudioOut.kt",
    "com/makefunstuff/herdr/voice/MicIn.kt",
    "com/makefunstuff/herdr/voice/PhoneSpeech.kt",
    "com/makefunstuff/herdr/voice/VoiceService.kt",
    "com/makefunstuff/herdr/voice/VoiceMic.kt",
    "com/makefunstuff/herdr/change/AndroidThisApp.kt",
    "com/makefunstuff/herdr/ui/change/ScreenShot.kt",
)

sourceSets.main {
    kotlin.srcDir("../../core/src/main/kotlin")
    kotlin.srcDir("../../app/src/main/kotlin")
    kotlin.exclude(androidOnly)
    // The app's fonts, so the screens are drawn in Geist and Geist Mono as on the phone.
    resources.srcDir("../../app/src/main/res/font")
}

tasks.register<JavaExec>("render") {
    group = "verification"
    description = "Renders every preview scene to build/shots/<scene>.png (-Ponly=a,b for some, -Pout=dir elsewhere, -Pshard=k/n for every n-th scene from the k-th)."
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("preview.MainKt")
    args = listOf(
        project.findProperty("out")?.toString() ?: layout.buildDirectory.dir("shots").get().asFile.path,
        project.findProperty("only")?.toString().orEmpty(),
        project.findProperty("shard")?.toString().orEmpty(),
    )
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}

plugins {
    id("bytedex.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.netty.all)
    implementation(libs.gson)
    implementation(libs.logback.classic)
}

application {
    mainClass = "org.openmmo.bytedex.proxy.ProxyKt"
}

// The proxy needs the matching private keys from the same `bytedex-keys.json`
// file the agent uses to swap public keys in the client. Pull the file from
// `:agent`'s generated output so we always agree on which keys are in play.
val copyAgentKeys by tasks.registering(Copy::class) {
    dependsOn(":agent:generateKeypairs")
    from(project(":agent").layout.buildDirectory.file("generated/resources/keys/bytedex-keys.json"))
    into(layout.buildDirectory.dir("generated/resources/keys"))
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/resources/keys"))
}

tasks.named("processResources") { dependsOn(copyAgentKeys) }

// `-Dbytedex.captureLog=<path>` on the gradlew command line only sets a system
// property on the Gradle JVM, not the forked app JVM `:run` launches -- forward
// it explicitly so per-session capture logs work (e.g. from an MCP capture tool).
tasks.named<JavaExec>("run") {
    System.getProperty("bytedex.captureLog")?.let { systemProperty("bytedex.captureLog", it) }
}

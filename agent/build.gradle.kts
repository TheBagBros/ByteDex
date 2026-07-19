plugins {
    java
    id("bytedex.sonarlint-conventions")
    id("com.gradleup.shadow")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    implementation(libs.byte.buddy)
    implementation(libs.byte.buddy.agent)
    implementation(libs.gson)
    implementation(libs.asm.tree)
}

val originalsFile = layout.projectDirectory.file("keys/originals.json")
val generatedKeysDir = layout.buildDirectory.dir("generated/resources/keys")
val generatedKeysFile = generatedKeysDir.map { it.file("bytedex-keys.json") }

val dnsConfigFile = layout.projectDirectory.file("config/dns-redirects.json")
val generatedDnsDir = layout.buildDirectory.dir("generated/resources/dns")

tasks.register<JavaExec>("generateKeypairs") {
    group = "build"
    description = "Generate EC P-256 keypairs for client public-key substitution."
    classpath = sourceSets["main"].output.classesDirs + sourceSets["main"].compileClasspath
    mainClass = "org.openmmo.bytedex.agent.tools.KeyGen"
    inputs.file(originalsFile)
    outputs.file(generatedKeysFile)
    args(
        "--originals", originalsFile.asFile.path,
        "--out", generatedKeysFile.get().asFile.path,
    )
}

val copyDnsConfig by tasks.registering(Copy::class) {
    from(dnsConfigFile) {
        rename { "bytedex-dns.json" }
    }
    into(generatedDnsDir)
}

sourceSets.main {
    resources.srcDir(generatedKeysDir)
    resources.srcDir(generatedDnsDir)
}

tasks.processResources {
    dependsOn("generateKeypairs", copyDnsConfig)
}

tasks.shadowJar {
    archiveBaseName = "bytedex-agent"
    archiveClassifier = ""
    archiveVersion = ""

    relocate("net.bytebuddy", "org.openmmo.bytedex.agent.shaded.bytebuddy")
    relocate("com.google.gson", "org.openmmo.bytedex.agent.shaded.gson")
    relocate("org.objectweb.asm", "net.bytebuddy.jar.asm")

    exclude("org/openmmo/bytedex/agent/tools/**")

    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Agent-Class" to "org.openmmo.bytedex.agent.Agent",
            "Premain-Class" to "org.openmmo.bytedex.agent.Agent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
            "Implementation-Title" to "bytedex-agent",
            "Implementation-Version" to project.version.toString(),
        )
    }

    mergeServiceFiles()
}

tasks.named("build") { dependsOn(tasks.shadowJar) }
tasks.named("jar") { enabled = false }

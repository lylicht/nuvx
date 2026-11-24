import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

apply(plugin = "com.android.library")


// nuvx-core reaches the Android and JVM code through core/rust/nuvx-ffi (UniFFI): cargo-ndk builds one .so per ABI,
// uniffi-bindgen writes the Kotlin bindings (package com.licht.nuvx.core) from that .so, and JVM tests load a host build.
abstract class NuvxFfiTask : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Internal
    abstract val crateDir: DirectoryProperty

    @get:Input
    abstract val command: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val ndkHome: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false } // cargo is incremental; it decides what to rebuild
    }

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        exec.exec {
            workingDir = crateDir.get().asFile
            commandLine(command.get().map { it.replace("{out}", out.path) })
            ndkHome.orNull?.let { environment("ANDROID_NDK_HOME", it) }
        }
    }
}

val nuvxFfiDir = rootProject.layout.projectDirectory.dir("core/rust/nuvx-ffi")
val androidNdkDir = extensions.getByType<LibraryAndroidComponentsExtension>().sdkComponents.ndkDirectory
val nuvxFfiAndroid = tasks.register<NuvxFfiTask>("cargoBuildNuvxFfi") {
    crateDir.set(nuvxFfiDir)
    ndkHome.set(androidNdkDir.map { it.asFile.path })
    command.set(
        listOf("cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86", "-t", "x86_64", "-o", "{out}") +
            listOf("build", "--release", "--locked"),
    )
    outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
}
val nuvxFfiHost = tasks.register<Exec>("cargoBuildNuvxFfiHost") {
    workingDir = nuvxFfiDir.asFile
    commandLine("cargo", "build", "--release", "--locked")
}
val nuvxFfiKotlin = tasks.register<NuvxFfiTask>("generateNuvxFfiKotlin") {
    dependsOn(nuvxFfiHost)
    crateDir.set(nuvxFfiDir)
    val hostLib = nuvxFfiDir.file("target/release/${System.mapLibraryName("nuvx_ffi")}").asFile.path
    command.set(
        listOf("cargo", "run", "--release", "--locked", "--features", "uniffi/cli", "--bin", "uniffi-bindgen", "--") +
            listOf("generate", "--library", hostLib, "--language", "kotlin", "--no-format", "--out-dir", "{out}"),
    )
    outputDir.set(layout.buildDirectory.dir("generated/uniffi/kotlin"))
}
extensions.configure<LibraryAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(nuvxFfiAndroid, NuvxFfiTask::outputDir)
    }
}
tasks.withType<Test>().configureEach {
    dependsOn(nuvxFfiHost)
    systemProperty("jna.library.path", nuvxFfiDir.dir("target/release").asFile.path)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir(nuvxFfiKotlin.flatMap { it.outputDir })
            dependencies {
                compileOnly("net.java.dev.jna:jna:5.19.1")
                compileOnly("com.microsoft.onnxruntime:onnxruntime:1.32.0")
                api("io.ktor:ktor-client-core:3.6.1")
                api("io.ktor:ktor-client-okhttp:3.6.1")
                api("io.ktor:ktor-client-websockets:3.6.1")
                api("io.ktor:ktor-client-content-negotiation:3.6.1")
                api("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                api("org.jsoup:jsoup:1.24.1")
                api("com.github.MetrolistGroup.innertubex:innertubex:v0.12.0")
                compileOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.27.0")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        androidMain.dependencies {
            implementation("net.java.dev.jna:jna:5.19.1@aar")
        }
        jvmMain.dependencies {
            implementation("net.java.dev.jna:jna:5.19.1")
        }
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:5.5.1")
            runtimeOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.27.0")
        }
    }
}

extensions.configure<LibraryExtension> {
    namespace = "com.licht.nuvx.shared"
    compileSdk = 37
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

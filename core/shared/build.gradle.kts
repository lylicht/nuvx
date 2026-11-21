import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

apply(plugin = "com.android.library")

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
            dependencies {
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
}

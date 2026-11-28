/*
 * The desktop's own Compose UI — the player, lyrics, queue and the Home /
 * Explore / Library / Search pages.
 *
 * It began as a copy of :sharedUi, which the phone, tablet and TV keep drawing
 * from. The two now move separately: a desktop redesign lands here and never
 * reaches the phone, and the phone's player never moves the desktop's. Packages
 * and the resource class are unchanged from :sharedUi, so the desktop's imports
 * did not move; that is safe only because no module depends on both.
 */
val cmp = "1.10.3"

/**
 * What the desktop graph resolves, and what this module is compiled against.
 *
 * Skia's Matrix33 became an inline float[9] between skiko 0.9 and 0.15
 * (`makeTranslate` went from returning a Matrix33 to a name-mangled float[]
 * overload), so bytecode compiled against 0.9 linked its object call and
 * crashed with NoSuchMethodError on a desktop running 0.15 the first time a
 * lyric line grew. Compiling against the version the desktop actually resolves
 * keeps the compile-time link and the runtime link the same.
 */
val CMP_DESKTOP = "1.12.2"

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // Kept from :sharedUi so the two trees line up file for file; with one
        // target it is simply the body of jvmMain.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            // The compatibility icon adapters are desktop-owned snapshots now.
            // Do not compile live Android/TV sharedUi sources into this application.
        }
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            api(project(":shared"))
            api("org.jetbrains.compose.runtime:runtime:$cmp")
            api("org.jetbrains.compose.foundation:foundation:$cmp")
            api("org.jetbrains.compose.animation:animation:$cmp")
            api("org.jetbrains.compose.ui:ui:$cmp")
            api("org.jetbrains.compose.material3:material3:1.9.0")
            // Selected Compose vectors from the catalog's Jetpack_SF_Symbols companion
            // are vendored in jvmSharedMain; see app/desktop/symbols-manifest.json.
            // Do not pull SFStudio's separate icon pack back onto the classpath.
            api("org.jetbrains.compose.components:components-resources:$cmp")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
            api("io.coil-kt.coil3:coil-compose:3.6.3")
            api("dev.chrisbanes.haze:haze:2.1.0")
            api("dev.chrisbanes.haze:haze-blur:2.1.0")
            api("dev.chrisbanes.haze:haze-blur-materials:2.1.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
        }
        // See CMP_DESKTOP above.
        jvmMain.dependencies {
            api("org.jetbrains.compose.ui:ui-desktop:$CMP_DESKTOP")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
            implementation("org.jetbrains.compose.ui:ui-test-junit4:$cmp")
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    publicResClass = true
    // The same class :sharedUi generates, so the desktop's `Res` imports did not move.
    packageOfResClass = "app.vyral.desktop.sharedui.resources"
    generateResClass = always
}

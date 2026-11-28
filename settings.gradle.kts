pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "VYRAL"

include(":shared", ":sharedUi", ":desktopKmpUi", ":desktopKmpApp")
project(":shared").projectDir = file("core/shared")
project(":sharedUi").projectDir = file("core/shared-ui")
project(":desktopKmpUi").projectDir = file("app/desktop/kmp/ui")
project(":desktopKmpApp").projectDir = file("app/desktop/kmp/app")

// Keep desktop builds usable on machines without an Android SDK. Wear OS is intentionally excluded.
val androidSdk: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) } }
    ?.getProperty("sdk.dir")
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
    ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app", ":tv")
    project(":app").projectDir = file("app/mobile")
    project(":tv").projectDir = file("app/tv")
} else {
    logger.lifecycle("No Android SDK found — Android mobile and TV projects are not included.")
}

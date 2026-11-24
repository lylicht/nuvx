buildscript {
    dependencies {
        classpath("com.android.tools.build:gradle:9.5.2") {
            exclude(group = "com.google.guava", module = "guava")
        }
        classpath("com.google.guava:guava:33.8.0-android")
    }

    configurations.classpath {
        // shortcut: force AGP 9.5.2's other vulnerable transitives; remove when a stable AGP release updates them.
        resolutionStrategy.force(
            "org.apache.commons:commons-lang3:3.22.0",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.apache.httpcomponents:httpmime:4.5.14",
            "org.bouncycastle:bcpkix-jdk18on:1.87",
            "org.bouncycastle:bcprov-jdk18on:1.87",
            "org.bouncycastle:bcutil-jdk18on:1.87",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.jdom:jdom2:2.0.6.1",
        )
    }
}

extra["nuvxVersionCode"] = providers.gradleProperty("nuvx.nightlyBaseCode").map(String::toInt).getOrElse(6)
extra["nuvxVersionName"] = providers.gradleProperty("nuvx.nightlyBaseName").getOrElse("1.0.4")

extra["nuvxBetaSuffix"] = providers.gradleProperty("nuvx.nightlySuffix").getOrElse("")

plugins {
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "org.apache.commons:commons-lang3:3.22.0",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.apache.httpcomponents:httpmime:4.5.14",
            "org.bouncycastle:bcpkix-jdk18on:1.87",
            "org.bouncycastle:bcprov-jdk18on:1.87",
            "org.bouncycastle:bcutil-jdk18on:1.87",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.jdom:jdom2:2.0.6.1",
        )
    }
}

package app.vyral.desktop

/** What this build is made of, and on what terms. */
internal data class DesktopLicense(
    val name: String,
    val license: String,
    val url: String,
    val note: String = "",
)

/** Grouped the way someone checking a licence actually reads. */
internal data class DesktopLicenseGroup(
    val title: String,
    val entries: List<DesktopLicense>,
)

internal object DesktopLicenses {

    val groups: List<DesktopLicenseGroup> = listOf(
        DesktopLicenseGroup(
            "This application",
            listOf(
                DesktopLicense(
                    "VYRAL",
                    "GPL-3.0-or-later",
                    "https://www.gnu.org/licenses/gpl-3.0.html",
                    "Your music, your way.",
                ),
            ),
        ),
        DesktopLicenseGroup(
            "Audio",
            listOf(
                DesktopLicense(
                    "Orchard audio analysis",
                    "AGPL-3.0-or-later",
                    "https://github.com/SFG5453/Orchard",
                    "Native analysis adapted from SFG545's implementation; see the bundled notices.",
                ),
                DesktopLicense(
                    "Beat This! and Open-Unmix UMX-HQ",
                    "MIT",
                    "https://github.com/bxanedot/vyral/blob/main/NOTICE.md",
                    "Analysis models by JKU Linz and Inria; their license texts are bundled with Vyral.",
                ),
                DesktopLicense(
                    "FFmpeg",
                    "LGPL-2.1-or-later",
                    "https://ffmpeg.org",
                    "Decoding, demuxing and resampling. The LGPL build is used " +
                        "deliberately; the GPL-enabled variant is not shipped.",
                ),
                DesktopLicense(
                    "JavaCPP / JavaCPP Presets",
                    "Apache-2.0",
                    "https://github.com/bytedeco/javacpp",
                    "The bridge from the JVM to FFmpeg.",
                ),
                DesktopLicense(
                    "OpenJFX",
                    "GPL-2.0-with-classpath-exception",
                    "https://openjfx.io",
                    "Video playback for animated cover art. Audio no longer goes through it.",
                ),
            ),
        ),
        DesktopLicenseGroup(
            "Application framework",
            listOf(
                DesktopLicense("Kotlin and kotlinx", "Apache-2.0", "https://kotlinlang.org"),
                DesktopLicense(
                    "Compose Multiplatform",
                    "Apache-2.0",
                    "https://www.jetbrains.com/lp/compose-multiplatform/",
                    "The user interface, shared with the Android application.",
                ),
                DesktopLicense("Ktor", "Apache-2.0", "https://ktor.io"),
                DesktopLicense(
                    "Haze",
                    "Apache-2.0",
                    "https://github.com/chrisbanes/haze",
                    "The backdrop blur behind the floating bars, as on Android.",
                ),
                DesktopLicense("SLF4J", "MIT", "https://www.slf4j.org"),
                DesktopLicense(
                    "Jetpack_SF_Symbols / SF Symbol Catalog",
                    "MIT (bindings); Apple terms (artwork)",
                    "https://github.com/cosmictaserdev-creator/Jetpack_SF_Symbols",
                    "SF Symbols Compose Contributors, via phranck/sf-symbols-lib. " +
                        "Apple owns the symbol artwork. See licenses/Jetpack-SF-Symbols.txt in the application.",
                ),
            ),
        ),
        DesktopLicenseGroup(
            "Sources and integration",
            listOf(
                DesktopLicense(
                    "NewPipeExtractor",
                    "GPL-3.0-or-later",
                    "https://github.com/TeamNewPipe/NewPipeExtractor",
                    "Stream URL deobfuscation, the same component the Android build uses.",
                ),
                DesktopLicense(
                    "GraalVM JavaScript Community",
                    "UPL-1.0 / MIT",
                    "https://www.graalvm.org",
                    "Runs source modules in a sandbox with no access to host classes.",
                ),
                DesktopLicense(
                    "dbus-java",
                    "MIT",
                    "https://github.com/hypfvieh/dbus-java",
                    "MPRIS media controls and the system tray icon and menu.",
                ),
                DesktopLicense(
                    "junixsocket",
                    "Apache-2.0",
                    "https://github.com/kohlschutter/junixsocket",
                    "Unix domain sockets for the session bus.",
                ),
            ),
        ),
    )
}

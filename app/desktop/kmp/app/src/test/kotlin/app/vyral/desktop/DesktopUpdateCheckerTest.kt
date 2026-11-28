package app.vyral.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.net.URI

class DesktopUpdateCheckerTest {

    @Test
    fun installerSuffixesMatchPublishedPackageNames() {
        assertEquals(
            listOf("-windows-x64-setup.exe", "-windows-x64.msi"),
            DesktopUpdateChecker.installerSuffixes("Windows 11", "amd64"),
        )
        assertEquals(
            listOf("-linux-amd64.deb", "-linux-x86_64.rpm"),
            DesktopUpdateChecker.installerSuffixes("Linux", "amd64"),
        )
        assertEquals(emptyList(), DesktopUpdateChecker.installerSuffixes("FreeBSD", "amd64"))
    }

    @Test
    fun doesNotOfferX64PackagesToUnsupportedArchitectures() {
        assertEquals(emptyList(), DesktopUpdateChecker.installerSuffixes("Windows 11", "aarch64"))
        assertEquals(emptyList(), DesktopUpdateChecker.installerSuffixes("Linux", "aarch64"))
    }

    @Test
    fun acceptsOnlyVyralReleaseAssetUrls() {
        assertTrue(DesktopUpdateChecker.isVyralReleaseAsset(URI("https://github.com/bxanedot/vyral/releases/download/v1.0.1/vyral.msi")))
        assertFalse(DesktopUpdateChecker.isVyralReleaseAsset(URI("http://github.com/bxanedot/vyral/releases/download/v1.0.1/vyral.msi")))
        assertFalse(DesktopUpdateChecker.isVyralReleaseAsset(URI("https://example.com/bxanedot/vyral/releases/download/v1.0.1/vyral.msi")))
        assertFalse(DesktopUpdateChecker.isVyralReleaseAsset(URI("https://github.com@evil.example/bxanedot/vyral/releases/download/v1.0.1/vyral.msi")))
    }
}

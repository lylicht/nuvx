package app.vyral.night

import app.vyral.night.ui.components.SUPPORTED_LANGUAGES
import app.vyral.night.ui.components.appLocaleTagForSelection
import app.vyral.night.ui.components.languageDisplayNameRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationTest {

    @Test
    fun supportedLanguages_containsVietnamese() {
        val viLanguage = SUPPORTED_LANGUAGES.firstOrNull { it.tag == "vi" }
        assertNotNull("Vietnamese language must be in SUPPORTED_LANGUAGES", viLanguage)
        assertEquals(R.string.vietnamese, viLanguage?.nameRes)
        assertEquals(R.string.vietnamese, languageDisplayNameRes("vi"))
    }

    @Test
    fun localesConfig_matchesSupportedLanguages() {
        val projectRoot = File(".").canonicalFile
        val resDir = if (File(projectRoot, "app/src/main/res").exists()) {
            File(projectRoot, "app/src/main/res")
        } else {
            File(projectRoot, "src/main/res")
        }
        val localesConfigFile = File(resDir, "xml/locales_config.xml")
        assertTrue("locales_config.xml must exist", localesConfigFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(localesConfigFile)
        val localeNodes = doc.getElementsByTagName("locale")

        val configuredLocales = mutableListOf<String>()
        for (i in 0 until localeNodes.length) {
            val node = localeNodes.item(i)
            val name = node.attributes.getNamedItem("android:name")?.nodeValue
            if (name != null) configuredLocales.add(name)
        }

        assertTrue("locales_config.xml must include 'vi'", configuredLocales.contains("vi"))

        for (lang in SUPPORTED_LANGUAGES.filter { it.tag.isNotBlank() }) {
            assertTrue(
                "locales_config.xml must include '${lang.tag}'",
                configuredLocales.contains(lang.tag),
            )
        }
    }

    @Test
    fun systemLanguageChoiceClearsTheExplicitAppLocaleOverride() {
        assertNull(appLocaleTagForSelection(""))
        assertNull(appLocaleTagForSelection("   "))
        assertEquals("fa", appLocaleTagForSelection("fa"))
        assertTrue(SUPPORTED_LANGUAGES.first().tag.isBlank())
        assertEquals(R.string.system_default, SUPPORTED_LANGUAGES.first().nameRes)
    }

    @Test
    fun persianResourcesCoverPhoneSharedUiAndAndroidTv() {
        val workingDirectory = File(".").canonicalFile
        val projectRoot = if (File(workingDirectory, "app/src/main/res").exists()) {
            workingDirectory
        } else {
            workingDirectory.parentFile
        }
        val appRes = File(projectRoot, "app/src/main/res")
        assertEquals(
            resourceKeys(File(appRes, "values/strings.xml")),
            resourceKeys(File(appRes, "values-fa/strings.xml")),
        )

        val sharedResources = File(projectRoot, "sharedUi/src/commonMain/composeResources")
        assertEquals(
            resourceKeys(File(sharedResources, "values/strings.xml")),
            resourceKeys(File(sharedResources, "values-fa/strings.xml")),
        )

        val locales = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(appRes, "xml/locales_config.xml"))
            .getElementsByTagName("locale")
        assertTrue(
            "Persian must be available to Android's system and per-app language pickers",
            (0 until locales.length).any {
                locales.item(it).attributes.getNamedItem("android:name")?.nodeValue == "fa"
            },
        )

        for (manifest in listOf(
            File(appRes, "../AndroidManifest.xml"),
            File(projectRoot, "tv/src/main/AndroidManifest.xml"),
        )) {
            val application = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(manifest)
                .getElementsByTagName("application")
                .item(0)
            assertEquals(
                "${manifest.name} must defer to Android's locale resolver and expose Persian",
                "@xml/locales_config",
                application.attributes.getNamedItem("android:localeConfig")?.nodeValue,
            )
        }
    }

    private fun resourceKeys(file: File): Set<String> {
        assertTrue("Missing localization resource: ${file.path}", file.exists())
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val keys = mutableSetOf<String>()
        for (index in 0 until root.childNodes.length) {
            val node = root.childNodes.item(index)
            val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
            keys += "${node.nodeName}:$name"
        }
        return keys
    }
}

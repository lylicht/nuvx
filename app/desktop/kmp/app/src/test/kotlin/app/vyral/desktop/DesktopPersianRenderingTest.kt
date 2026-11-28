package app.vyral.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopPersianRenderingTest {
    @Test fun `Persian selection renders translated text in RTL then restores English LTR`() = runComposeUiTest {
        val before = DesktopStrings.language.value
        try {
            DesktopStrings.setLanguage("fa")
            setContent {
                val language by DesktopStrings.language.collectAsState()
                key(language) {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (DesktopStrings.isRtl()) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        Row(Modifier.width(320.dp)) {
                            Text(DesktopStrings["settings"], Modifier.testTag("label"))
                            Text("VYRAL 123", Modifier.testTag("latin"))
                        }
                    }
                }
            }
            onNodeWithText("تنظیمات").assertExists()
            assertTrue(onNodeWithTag("label").fetchSemanticsNode().boundsInRoot.left >
                onNodeWithTag("latin").fetchSemanticsNode().boundsInRoot.left)
            runOnIdle { DesktopStrings.setLanguage("en") }
            onNodeWithText("Settings").assertExists()
            assertTrue(onNodeWithTag("label").fetchSemanticsNode().boundsInRoot.left <
                onNodeWithTag("latin").fetchSemanticsNode().boundsInRoot.left)
        } finally {
            DesktopStrings.setLanguage(before)
        }
    }
}

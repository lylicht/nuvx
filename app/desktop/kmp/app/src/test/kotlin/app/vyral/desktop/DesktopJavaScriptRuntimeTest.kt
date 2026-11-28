package app.vyral.desktop

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopJavaScriptRuntimeTest {
    @Test
    fun communityRuntimeRunsJavaScriptWithoutHostAccess() {
        Context.newBuilder("js")
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup { false }
            .build().use { context ->
                assertEquals(42, context.eval("js", "[6, 7].reduce((a, b) => a * b, 1)").asInt())
                assertFailsWith<PolyglotException> {
                    context.eval("js", "Java.type('java.lang.System').getProperty('user.home')")
                }
            }
    }
}

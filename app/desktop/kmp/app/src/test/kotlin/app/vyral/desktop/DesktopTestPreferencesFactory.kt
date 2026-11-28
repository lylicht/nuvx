package app.vyral.desktop

import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

/** Cross-platform test storage; userRoot alone does not isolate Windows Registry preferences. */
class DesktopTestPreferencesFactory : PreferencesFactory {
    private val user = MemoryPreferences(null, "")
    private val system = MemoryPreferences(null, "")
    override fun userRoot(): Preferences = user
    override fun systemRoot(): Preferences = system
}

private class MemoryPreferences(parent: AbstractPreferences?, name: String) :
    AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>()
    private val children = mutableMapOf<String, MemoryPreferences>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() {
        values.clear()
        children.clear()
        (parent() as? MemoryPreferences)?.children?.remove(name())
    }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
    override fun childSpi(name: String): AbstractPreferences =
        children.getOrPut(name) { MemoryPreferences(this, name) }
    override fun syncSpi() = Unit
    override fun flushSpi() = Unit
}

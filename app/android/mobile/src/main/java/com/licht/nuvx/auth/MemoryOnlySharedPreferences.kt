package com.licht.nuvx.auth

import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/** Process-only fallback used when Android Keystore cannot provide encrypted persistence. */
internal class MemoryOnlySharedPreferences : SharedPreferences {
    private val lock = Any()
    private val values = mutableMapOf<String, Any>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = synchronized(lock) { values.toMap() }
    override fun getString(key: String, defValue: String?): String? = synchronized(lock) { values[key] as? String ?: defValue }
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = synchronized(lock) {
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues?.toSet()
    }
    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) { values[key] as? Int ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) { values[key] as? Long ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) { values[key] as? Float ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) { values[key] as? Boolean ?: defValue }
    override fun contains(key: String): Boolean = synchronized(lock) { key in values }
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += listener }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= listener }

    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = put(key, values?.toSet())
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean = applyChanges()
        override fun apply() { applyChanges() }

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply { updates[key] = value }

        private fun applyChanges(): Boolean {
            val changed = synchronized(lock) {
                val keys = linkedSetOf<String>()
                if (clear) {
                    keys += values.keys
                    values.clear()
                }
                updates.forEach { (key, value) ->
                    if (value == null) {
                        if (values.remove(key) != null) keys += key
                    } else if (values[key] != value) {
                        values[key] = value
                        keys += key
                    }
                }
                keys
            }
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@MemoryOnlySharedPreferences, key) } }
            return true
        }
    }
}

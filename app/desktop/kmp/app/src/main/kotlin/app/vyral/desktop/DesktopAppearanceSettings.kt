package app.vyral.desktop

import androidx.compose.ui.graphics.Color
import app.vyral.desktop.brand.VyralAccentTheme
import app.vyral.desktop.brand.VyralLogoStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun resolveDesktopAccent(
    theme: VyralAccentTheme,
    customHue: Float,
    artworkTint: Color?,
    logoStyle: VyralLogoStyle,
): Color = artworkTint ?: if (theme == VyralAccentTheme.CUSTOM) {
    Color.hsl(customHue, .8f, .52f)
} else Color(logoStyle.accentArgb)

internal fun normalizeDesktopInterfaceScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.85f, 1.2f) else 1f

internal fun normalizeDesktopSidebarWidth(value: Float): Float =
    if (value.isFinite()) value.coerceIn(200f, 320f) else 220f

/**
 * The toggles read far from the Settings sheet that sets them.
 *
 * Mirrors Android's `AppSettings` for these few: a composable deep in the player or the lyrics
 * pane can collect one without it being threaded down through every caller.
 */
internal object DesktopAppearanceSettings {

    private fun flag(key: String, default: Boolean) =
        MutableStateFlow(DesktopPersistence().boolean(key, default))

    private val _reduceAnimation = flag(KEY_REDUCE_ANIMATION, false)
    private val _reduceDynamicBlur = flag(KEY_REDUCE_DYNAMIC_BLUR, false)
    private val _hideVolumeBar = flag(KEY_HIDE_VOLUME_BAR, false)
    private val _ambientBackdrop = flag(KEY_AMBIENT_BACKDROP, false)
    private val _interfaceScale = MutableStateFlow(
        normalizeDesktopInterfaceScale(DesktopPersistence().float(KEY_INTERFACE_SCALE, 1f)),
    )
    private val _sidebarWidth = MutableStateFlow(
        normalizeDesktopSidebarWidth(DesktopPersistence().float(KEY_SIDEBAR_WIDTH, 220f)),
    )
    private val _logoStyle = MutableStateFlow(VyralLogoStyle.fromStored(DesktopPersistence().string(KEY_LOGO_STYLE)))
    private val _accentTheme = MutableStateFlow(VyralAccentTheme.fromStored(DesktopPersistence().string(KEY_ACCENT_THEME)))
    private val _customAccentHue = MutableStateFlow(DesktopPersistence().string(KEY_CUSTOM_ACCENT_HUE, "210").toFloatOrNull()?.mod(360f) ?: 210f)

    /** Freezes the main player's gradient instead of drifting. */
    val reduceAnimation: StateFlow<Boolean> = _reduceAnimation

    /** Swaps frosted glass for solid fills across the app. */
    val reduceDynamicBlur: StateFlow<Boolean> = _reduceDynamicBlur

    /** Removes the volume slider from the main player. */
    val hideVolumeBar: StateFlow<Boolean> = _hideVolumeBar

    /** Diffuses current artwork colors under the window glass chrome like Apple Music. */
    val ambientBackdrop: StateFlow<Boolean> = _ambientBackdrop
    /** Scales the complete desktop UI while preserving the operating system's display scale. */
    val interfaceScale: StateFlow<Float> = _interfaceScale
    /** Width of the navigation sidebar, in Compose dp. */
    val sidebarWidth: StateFlow<Float> = _sidebarWidth
    val logoStyle: StateFlow<VyralLogoStyle> = _logoStyle
    val accentTheme: StateFlow<VyralAccentTheme> = _accentTheme
    val customAccentHue: StateFlow<Float> = _customAccentHue

    fun setReduceAnimation(value: Boolean) = write(KEY_REDUCE_ANIMATION, value, _reduceAnimation)

    fun setReduceDynamicBlur(value: Boolean) = write(KEY_REDUCE_DYNAMIC_BLUR, value, _reduceDynamicBlur)

    fun setHideVolumeBar(value: Boolean) = write(KEY_HIDE_VOLUME_BAR, value, _hideVolumeBar)

    fun setAmbientBackdrop(value: Boolean) = write(KEY_AMBIENT_BACKDROP, value, _ambientBackdrop)

    fun setInterfaceScale(value: Float) {
        val scale = normalizeDesktopInterfaceScale(value)
        DesktopPersistence().saveFloat(KEY_INTERFACE_SCALE, scale)
        _interfaceScale.value = scale
    }

    fun setSidebarWidth(value: Float) {
        val width = normalizeDesktopSidebarWidth(value)
        DesktopPersistence().saveFloat(KEY_SIDEBAR_WIDTH, width)
        _sidebarWidth.value = width
    }

    fun setLogoStyle(value: VyralLogoStyle) {
        val persistence = DesktopPersistence()
        persistence.saveString(KEY_LOGO_STYLE, value.name)
        persistence.saveString(KEY_ACCENT_THEME, VyralAccentTheme.DYNAMIC.name)
        _logoStyle.value = value
        _accentTheme.value = VyralAccentTheme.DYNAMIC
    }

    fun setAccentTheme(value: VyralAccentTheme) {
        DesktopPersistence().saveString(KEY_ACCENT_THEME, value.name)
        _accentTheme.value = value
    }

    fun setCustomAccentHue(value: Float) {
        val hue = value.coerceIn(0f, 360f)
        DesktopPersistence().saveString(KEY_CUSTOM_ACCENT_HUE, hue.toString())
        DesktopPersistence().saveString(KEY_ACCENT_THEME, VyralAccentTheme.CUSTOM.name)
        _customAccentHue.value = hue
        _accentTheme.value = VyralAccentTheme.CUSTOM
    }

    private fun write(key: String, value: Boolean, into: MutableStateFlow<Boolean>) {
        DesktopPersistence().saveBoolean(key, value)
        into.value = value
    }

    internal const val KEY_REDUCE_ANIMATION = "reduce_animation"
    internal const val KEY_REDUCE_DYNAMIC_BLUR = "reduce_dynamic_blur"
    internal const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    internal const val KEY_AMBIENT_BACKDROP = "ambient_backdrop"
    internal const val KEY_INTERFACE_SCALE = "desktop_interface_scale"
    internal const val KEY_SIDEBAR_WIDTH = "desktop_sidebar_width"
    private const val KEY_LOGO_STYLE = "vyral_logo_style"
    private const val KEY_ACCENT_THEME = "vyral_accent_theme"
    private const val KEY_CUSTOM_ACCENT_HUE = "vyral_custom_accent_hue"
}

package app.vyral.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import app.vyral.desktop.brand.VyralLogoStyle
import app.vyral.desktop.sharedui.resources.Res
import app.vyral.desktop.sharedui.resources.vyral_logo_blue_gold
import app.vyral.desktop.sharedui.resources.vyral_logo_chrome_multicolor
import app.vyral.desktop.sharedui.resources.vyral_logo_gold_silver
import app.vyral.desktop.sharedui.resources.vyral_logo_iridescent_blue_pink
import app.vyral.desktop.sharedui.resources.vyral_logo_blue_gold_vector
import app.vyral.desktop.sharedui.resources.vyral_logo_chrome_multicolor_vector
import app.vyral.desktop.sharedui.resources.vyral_logo_gold_silver_vector
import app.vyral.desktop.sharedui.resources.vyral_logo_iridescent_blue_pink_vector
import app.vyral.desktop.sharedui.resources.vyral_logo_mark
import app.vyral.desktop.sharedui.resources.vyral_logo_pearlescent_vector
import app.vyral.desktop.sharedui.resources.vyral_logo_psychedelic_gloss_vector
import app.vyral.desktop.sharedui.resources.vyral_logo_teal_pink_vector
import app.vyral.desktop.sharedui.resources.vyral_logo_mark
import app.vyral.desktop.sharedui.resources.vyral_logo_pearlescent
import app.vyral.desktop.sharedui.resources.vyral_logo_psychedelic_gloss
import app.vyral.desktop.sharedui.resources.vyral_logo_teal_pink
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_blood_red
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_cobalt
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_coral
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_emerald
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_gold
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_indigo
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_lime
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_magenta
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_orange
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_purple
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_rose
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_sky_blue
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_turquoise
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_white
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

val LocalVyralLogoStyle = staticCompositionLocalOf { VyralLogoStyle.SOLID_WHITE }

@Composable
fun VyralLogo(
    modifier: Modifier = Modifier.size(64.dp),
    contentDescription: String? = "vyral",
    style: VyralLogoStyle = LocalVyralLogoStyle.current,
) {
    val isSolid = style.name.startsWith("SOLID_")
    val mark = when (style) {
        VyralLogoStyle.IRIDESCENT_BLUE_PINK -> Res.drawable.vyral_logo_iridescent_blue_pink_vector
        VyralLogoStyle.CHROME_MULTICOLOR -> Res.drawable.vyral_logo_chrome_multicolor_vector
        VyralLogoStyle.TEAL_PINK -> Res.drawable.vyral_logo_teal_pink_vector
        VyralLogoStyle.BLUE_GOLD -> Res.drawable.vyral_logo_blue_gold_vector
        VyralLogoStyle.PSYCHEDELIC_GLOSS -> Res.drawable.vyral_logo_psychedelic_gloss_vector
        VyralLogoStyle.PEARLESCENT -> Res.drawable.vyral_logo_pearlescent_vector
        VyralLogoStyle.GOLD_SILVER -> Res.drawable.vyral_logo_gold_silver_vector
        else -> Res.drawable.vyral_logo_mark
    }
    val visibleModifier = if (style == VyralLogoStyle.SOLID_WHITE && MaterialTheme.colorScheme.surface.luminance() > .5f) {
        modifier.clip(RoundedCornerShape(percent = 22)).background(Color(0xFF161719))
    } else modifier
    Image(
        painterResource(mark),
        contentDescription,
        visibleModifier,
        colorFilter = if (isSolid) ColorFilter.tint(Color(style.accentArgb)) else null,
    )
}

fun vyralLogoResource(style: VyralLogoStyle): DrawableResource = when (style) {
    VyralLogoStyle.IRIDESCENT_BLUE_PINK -> Res.drawable.vyral_logo_iridescent_blue_pink
    VyralLogoStyle.CHROME_MULTICOLOR -> Res.drawable.vyral_logo_chrome_multicolor
    VyralLogoStyle.TEAL_PINK -> Res.drawable.vyral_logo_teal_pink
    VyralLogoStyle.BLUE_GOLD -> Res.drawable.vyral_logo_blue_gold
    VyralLogoStyle.PSYCHEDELIC_GLOSS -> Res.drawable.vyral_logo_psychedelic_gloss
    VyralLogoStyle.PEARLESCENT -> Res.drawable.vyral_logo_pearlescent
    VyralLogoStyle.GOLD_SILVER -> Res.drawable.vyral_logo_gold_silver
    VyralLogoStyle.SOLID_BLOOD_RED -> Res.drawable.vyral_logo_solid_blood_red
    VyralLogoStyle.SOLID_COBALT -> Res.drawable.vyral_logo_solid_cobalt
    VyralLogoStyle.SOLID_CORAL -> Res.drawable.vyral_logo_solid_coral
    VyralLogoStyle.SOLID_EMERALD -> Res.drawable.vyral_logo_solid_emerald
    VyralLogoStyle.SOLID_GOLD -> Res.drawable.vyral_logo_solid_gold
    VyralLogoStyle.SOLID_INDIGO -> Res.drawable.vyral_logo_solid_indigo
    VyralLogoStyle.SOLID_LIME -> Res.drawable.vyral_logo_solid_lime
    VyralLogoStyle.SOLID_MAGENTA -> Res.drawable.vyral_logo_solid_magenta
    VyralLogoStyle.SOLID_ORANGE -> Res.drawable.vyral_logo_solid_orange
    VyralLogoStyle.SOLID_PURPLE -> Res.drawable.vyral_logo_solid_purple
    VyralLogoStyle.SOLID_ROSE -> Res.drawable.vyral_logo_solid_rose
    VyralLogoStyle.SOLID_SKY_BLUE -> Res.drawable.vyral_logo_solid_sky_blue
    VyralLogoStyle.SOLID_TURQUOISE -> Res.drawable.vyral_logo_solid_turquoise
    VyralLogoStyle.SOLID_WHITE -> Res.drawable.vyral_logo_solid_white
}

fun vyralLogoResourcePath(style: VyralLogoStyle): String =
    "drawable/vyral_logo_${style.name.lowercase()}.png"

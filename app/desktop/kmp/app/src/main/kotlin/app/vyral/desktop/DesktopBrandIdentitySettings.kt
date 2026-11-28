package app.vyral.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vyral.desktop.brand.VyralAccentTheme
import app.vyral.desktop.brand.VyralAccentPalette
import app.vyral.desktop.brand.VyralLogoStyle
import app.vyral.desktop.ui.components.VyralLogo

@Composable
internal fun DesktopBrandIdentitySettings() {
    val logoStyle by DesktopAppearanceSettings.logoStyle.collectAsState()
    val accentTheme by DesktopAppearanceSettings.accentTheme.collectAsState()
    val customAccentHue by DesktopAppearanceSettings.customAccentHue.collectAsState()

    Text("vyral mark", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VyralLogoStyle.entries.forEach { style ->
            FilterChip(
                selected = logoStyle == style,
                onClick = { DesktopAppearanceSettings.setLogoStyle(style) },
                colors = desktopChipColors(),
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF161719)),
                            contentAlignment = Alignment.Center,
                        ) {
                            VyralLogo(Modifier.size(27.dp), contentDescription = null, style = style)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(style.label, maxLines = 1)
                    }
                },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text("Accent palette", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VyralAccentTheme.entries.forEach { theme ->
            FilterChip(
                selected = accentTheme == theme,
                onClick = { DesktopAppearanceSettings.setAccentTheme(theme) },
                colors = desktopChipColors(),
                label = { Text(theme.label) },
            )
        }
    }
    if (accentTheme == VyralAccentTheme.CUSTOM) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VyralAccentPalette.forEach { swatch ->
                val selected = customAccentHue == swatch.hue
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.hsl(swatch.hue, .8f, .52f))
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        )
                        .semantics { contentDescription = "${swatch.label} accent" }
                        .selectable(selected, role = Role.RadioButton) {
                            DesktopAppearanceSettings.setCustomAccentHue(swatch.hue)
                        },
                )
            }
        }
        Slider(
            value = customAccentHue,
            onValueChange = DesktopAppearanceSettings::setCustomAccentHue,
            valueRange = 0f..360f,
        )
    }
}

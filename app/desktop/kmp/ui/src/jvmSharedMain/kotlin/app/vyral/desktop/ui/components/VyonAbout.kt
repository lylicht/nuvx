package app.vyral.desktop.ui.components

import app.vyral.night.ui.icons.NyvoIcon as Icon
import app.vyral.night.ui.icons.VyralIcons

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vyral.desktop.sharedui.resources.*
import app.vyral.night.data.Http
import app.vyral.desktop.ui.icons.VyralBrandIcons
import app.vyral.desktop.ui.icons.VyralSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun VyralAbout(versionName: String, versionCode: Int, modifier: Modifier = Modifier, onLicenses: (() -> Unit)? = null) {
    val uri = LocalUriHandler.current
    var stars by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        stars = withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(Request.Builder().url("https://api.github.com/repos/bxanedot/vyral").header("Accept", "application/vnd.github+json").build())
                    .execute().use { response ->
                        if (!response.isSuccessful) -1
                        else Json.parseToJsonElement(response.body.string()).jsonObject["stargazers_count"]?.jsonPrimitive?.intOrNull ?: -1
                    }
            }.getOrDefault(-1)
        }
    }
    val displayedStars by animateIntAsState(stars.coerceAtLeast(0), animationSpec = tween(900), label = "github-stars")
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VyralLogo(Modifier.size(88.dp))
        Text("vyral", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Text("v$versionName · " + stringResource(Res.string.vyral_stable), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(Res.string.vyral_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        AboutSection(stringResource(Res.string.vyral_lead_dev)) {
            AboutPerson(painterResource(Res.drawable.bxane_avatar), "bxane", stringResource(Res.string.vyral_lead_dev), VyralIcons.Code) {
                uri.openUri("https://github.com/bxanedot")
            }
        }
        AboutSection(stringResource(Res.string.vyral_quality_assurance_tester)) {
            AboutPerson(painterResource(Res.drawable.vantom_avatar), "Vantom", stringResource(Res.string.vyral_quality_assurance_tester), VyralIcons.Info) {
                uri.openUri("https://guns.lol/vantom0101")
            }
        }
        AboutSection(stringResource(Res.string.vyral_app_info)) {
            AboutRow(VyralSymbols.Version, stringResource(Res.string.vyral_version_code), versionCode.toString())
            HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            AboutRow(VyralSymbols.Website, stringResource(Res.string.vyral_website), "vyral.live") {
                uri.openUri("https://vyral.live")
            }
            HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            AboutRow(VyralBrandIcons.GitHub, stringResource(Res.string.vyral_source), "github.com/bxanedot/vyral") {
                uri.openUri("https://github.com/bxanedot/vyral")
            }
            onLicenses?.let {
            AboutRow(VyralSymbols.About, stringResource(Res.string.vyral_open_source_licenses), null, onClick = it)
            }
        }
        AboutSection(stringResource(Res.string.vyral_community)) {
            AboutRow(null, "Discord", null, painter = painterResource(Res.drawable.discord)) { uri.openUri("https://discord.gg/h4Wsu824Uf") }
            HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            AboutRow(null, "Telegram", null, painter = painterResource(Res.drawable.telegram)) { uri.openUri("https://t.me/+T9avk7iJ7_YyN2Rk") }
        }
        AboutSection("GitHub") {
            AboutRow(VyralBrandIcons.GitHub, stringResource(Res.string.vyral_github_stars), if (stars < 0) "—" else "$displayedStars ★") {
                uri.openUri("https://github.com/bxanedot/vyral")
            }
        }
        AboutSection(stringResource(Res.string.vyral_support)) {
            AboutRow(null, "Patreon", null, painter = painterResource(Res.drawable.patreon)) { uri.openUri("https://www.patreon.com/cw/bxane") }
            HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            AboutRow(null, "Ko-fi", null, painter = painterResource(Res.drawable.kofi)) { uri.openUri("https://ko-fi.com/bxane") }
            HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            AboutRow(null, "bxane", "guns.lol/bxane", painter = painterResource(Res.drawable.guns_lol)) { uri.openUri("https://guns.lol/bxane") }
        }
        Text("GPL v3 · Not affiliated with YouTube or Google.", Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, bottom = 7.dp, top = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), content = content)
    }
}

@Composable
private fun AboutPerson(painter: androidx.compose.ui.graphics.painter.Painter, name: String, role: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painter, "$name profile", Modifier.size(42.dp).clip(CircleShape))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AboutRow(icon: ImageVector?, title: String, detail: String?, painter: androidx.compose.ui.graphics.painter.Painter? = null, onClick: (() -> Unit)? = null) {
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 15.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else painter?.let { Icon(it, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End) }
            if (onClick != null) Icon(VyralSymbols.Navigate, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f))
        }
    }
    if (onClick != null) Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceVariant, content = row)
    else Surface(color = MaterialTheme.colorScheme.surfaceVariant, content = row)
}

@Composable
fun VyralReservedListening(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        VyralLogo(Modifier.size(120.dp))
        Spacer(Modifier.height(20.dp))
        Text("Listen Together", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(Res.string.vyral_reserved), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(Res.string.vyral_reserved_detail), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

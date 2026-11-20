package com.samaveta.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.samaveta.android.ui.design.HairlineDivider
import com.samaveta.android.ui.design.SamavetaTheme
import com.samaveta.android.ui.design.Spacing
import com.samaveta.android.ui.design.ThemeMode
import com.samaveta.android.ui.design.TypographyTokens
import com.samaveta.android.ui.design.samaveta
import com.samaveta.android.ui.screens.ActivityScreen
import com.samaveta.android.ui.screens.AgentScreen
import com.samaveta.android.ui.screens.AutomationScreen
import com.samaveta.android.ui.screens.BrowserScreen
import com.samaveta.android.ui.screens.IntelligenceScreen
import com.samaveta.android.ui.screens.MemoryScreen
import com.samaveta.android.ui.screens.ModelsScreen
import com.samaveta.android.ui.screens.SettingsScreen
import com.samaveta.android.ui.screens.TodayScreen
import com.samaveta.android.ui.screens.VoiceLabScreen

/**
 * Application shell.
 *
 * Navigation choice: SAMAVETA has ten surfaces but only five belong in the persistent bar. The
 * primary bar carries Today / Agent / Intelligence / Activity; everything else (Memory, Models,
 * Voice Lab, Browser, Automation, Settings) lives behind the "More" surface so the bar stays
 * legible on a 360dp-wide phone rather than becoming an icon soup.
 *
 * The app is a single-activity, state-driven composition rather than a Nav graph: with ten
 * top-level destinations there is no deep back-stack to model, and this keeps tab state alive
 * without re-inflating screens.
 */
@Composable
fun SamavetaRoot(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    SamavetaTheme(themeMode = themeMode) {
        var destination by rememberSaveable { mutableStateOf(Destination.TODAY) }

        Scaffold(
            containerColor = MaterialTheme.samaveta.canvas,
            bottomBar = {
                BottomBar(
                    current = destination,
                    onSelect = { destination = it },
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            ) {
                // Padding is applied per-screen: the bottom bar is opaque, so screens add its
                // height themselves and lists can scroll underneath nothing.
                ScreenContent(
                    destination = destination,
                    onNavigate = { destination = it },
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    bottomInset = padding.calculateBottomPadding(),
                )
            }
        }
    }
}

@Composable
private fun ScreenContent(
    destination: Destination,
    onNavigate: (Destination) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    bottomInset: Dp,
) {
    when (destination) {
        Destination.TODAY -> TodayScreen(onNavigate = onNavigate, bottomInset = bottomInset)
        Destination.AGENT -> AgentScreen(bottomInset = bottomInset)
        Destination.ACTIVITY -> ActivityScreen(bottomInset = bottomInset)
        Destination.INTELLIGENCE -> IntelligenceScreen(bottomInset = bottomInset)
        Destination.MEMORY -> MemoryScreen(bottomInset = bottomInset)
        Destination.MODELS -> ModelsScreen(bottomInset = bottomInset)
        Destination.VOICE -> VoiceLabScreen(bottomInset = bottomInset)
        Destination.BROWSER -> BrowserScreen(bottomInset = bottomInset)
        Destination.AUTOMATION -> AutomationScreen(bottomInset = bottomInset)
        Destination.SETTINGS -> SettingsScreen(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onNavigate = onNavigate,
            bottomInset = bottomInset,
        )
    }
}

enum class Destination(
    val label: String,
    val icon: ImageVector,
    val inBottomBar: Boolean,
) {
    TODAY("Today", Icons.Outlined.WbSunny, true),
    AGENT("Agent", Icons.Outlined.SmartToy, true),
    INTELLIGENCE("Intelligence", Icons.Outlined.AutoAwesome, true),
    ACTIVITY("Activity", Icons.Outlined.History, true),
    MEMORY("Memory", Icons.Outlined.Psychology, false),
    MODELS("Models", Icons.Outlined.Inventory2, false),
    VOICE("Voice Lab", Icons.Outlined.Waves, false),
    BROWSER("Browser", Icons.Outlined.Language, false),
    AUTOMATION("Automation", Icons.Outlined.Settings, false),
    SETTINGS("Settings", Icons.Outlined.Settings, false),
    ;

    companion object {
        val bar: List<Destination> get() = entries.filter { it.inBottomBar }
        val overflow: List<Destination> get() = entries.filter { !it.inBottomBar }
    }
}

@Composable
private fun BottomBar(current: Destination, onSelect: (Destination) -> Unit) {
    val colors = MaterialTheme.samaveta
    var moreOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        HairlineDivider(inset = 0.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Destination.bar.forEach { destination ->
                BarItem(
                    destination = destination,
                    selected = current == destination,
                    onClick = { moreOpen = false; onSelect(destination) },
                )
            }
            BarItem(
                destination = Destination.SETTINGS,
                selected = moreOpen,
                forceLabel = "More",
                forceIcon = Icons.Outlined.Settings,
                onClick = { moreOpen = !moreOpen },
            )
        }

        if (moreOpen) {
            Column(Modifier.fillMaxWidth()) {
                HairlineDivider(inset = Spacing.lg)
                Destination.overflow.filter { it != Destination.SETTINGS }.forEach { destination ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { moreOpen = false; onSelect(destination) }
                            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            destination.icon,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(destination.label, style = TypographyTokens.body, color = colors.textPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun BarItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
    forceLabel: String? = null,
    forceIcon: ImageVector? = null,
) {
    val colors = MaterialTheme.samaveta
    val tint = if (selected) colors.accent else colors.textTertiary
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Icon(
            imageVector = forceIcon ?: destination.icon,
            contentDescription = forceLabel ?: destination.label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = forceLabel ?: destination.label,
            style = TypographyTokens.label,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        // Selection is indicated by colour plus a 2dp underline so it does not rely on colour alone.
        Box(
            Modifier
                .width(14.dp)
                .height(2.dp)
                .background(if (selected) colors.accent else Color.Transparent),
        )
    }
}

/** Shared page scaffold: title, optional subtitle, optional trailing content. */
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.samaveta
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xl, bottom = Spacing.lg),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = TypographyTokens.display, color = colors.textPrimary)
                if (subtitle != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(subtitle, style = TypographyTokens.body, color = colors.textSecondary)
                }
            }
            trailing?.invoke()
        }
        content()
    }
}

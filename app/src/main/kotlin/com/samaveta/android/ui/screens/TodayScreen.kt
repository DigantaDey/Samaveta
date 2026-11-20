package com.samaveta.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.samaveta.android.core.device.DeviceCapabilities
import com.samaveta.android.core.device.DeviceProfile
import com.samaveta.android.core.device.MemoryTier
import com.samaveta.android.core.laya.LayaIntegrationState
import com.samaveta.android.ui.Destination
import com.samaveta.android.ui.PageScaffold
import com.samaveta.android.ui.design.EmptyState
import com.samaveta.android.ui.design.HairlineDivider
import com.samaveta.android.ui.design.Radius
import com.samaveta.android.ui.design.SectionHeader
import com.samaveta.android.ui.design.Spacing
import com.samaveta.android.ui.design.StatusPill
import com.samaveta.android.ui.design.StatusTone
import com.samaveta.android.ui.design.TypographyTokens
import com.samaveta.android.ui.design.samaveta
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Today" — the home surface.
 *
 * Composition rule: the screen answers three questions in order of urgency —
 *   1. Is anything wrong? (device constraints, missing models, paused automation)
 *   2. What deserves my attention right now? (top intelligence cards)
 *   3. What has SAMAVETA been doing? (recent verified activity)
 *
 * Nothing on this screen is seeded with sample data. When there is no content the empty states
 * explain exactly why and what will make it appear.
 */
@Composable
fun TodayScreen(
    onNavigate: (Destination) -> Unit,
    bottomInset: Dp,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Read the nullable property once: `state` is delegated Compose state, so repeated property
    // reads inside the LazyColumn builder cannot be smart-cast safely by Kotlin.
    val deviceNote = state.deviceNote
    val colors = MaterialTheme.samaveta

    PageScaffold(
        title = "Today",
        subtitle = state.greeting,
        trailing = {
            StatusPill(
                label = state.layaLabel,
                tone = state.layaTone,
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + Spacing.xxl),
        ) {
            if (state.alerts.isNotEmpty()) {
                item { SectionHeader("Needs attention") }
                items(state.alerts) { alert ->
                    AttentionRow(alert)
                }
                item { Spacer(Modifier.height(Spacing.sm)) }
            }

            item { SectionHeader("Worth your attention") }
            if (state.topCards.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Inbox,
                        title = "No digest yet",
                        body = "Add topics you care about in Intelligence. SAMAVETA will rank coverage against them and show the provenance of every story.",
                        actionLabel = "Set up topics",
                        onAction = { onNavigate(Destination.INTELLIGENCE) },
                    )
                }
            } else {
                items(state.topCards, key = { it.id }) { card ->
                    TodayCardRow(card) { onNavigate(Destination.INTELLIGENCE) }
                }
            }

            item { Spacer(Modifier.height(Spacing.lg)) }
            item { SectionHeader("Recent activity") }
            if (state.recentActivity.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Schedule,
                        title = "Nothing has run yet",
                        body = "Tasks SAMAVETA performs — and their verification result — will appear in Activity as they happen.",
                        actionLabel = "Open Activity",
                        onAction = { onNavigate(Destination.ACTIVITY) },
                    )
                }
            } else {
                items(state.recentActivity, key = { it.id }) { item -> ActivityRow(item) }
                item {
                    Text(
                        "View all",
                        style = TypographyTokens.bodyEmphasis,
                        color = colors.accent,
                        modifier = Modifier
                            .clickable { onNavigate(Destination.ACTIVITY) }
                            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                    )
                }
            }

            if (deviceNote != null) {
                item { Spacer(Modifier.height(Spacing.lg)) }
                item { SectionHeader("This device") }
                item { DeviceNoteRow(deviceNote) }
            }
        }
    }
}

@Composable
private fun AttentionRow(alert: TodayAlert) {
    val colors = MaterialTheme.samaveta
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(if (alert.severity == StatusTone.DANGER) colors.danger else colors.caution),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(alert.title, style = TypographyTokens.bodyEmphasis, color = colors.textPrimary)
            Spacer(Modifier.height(Spacing.xxs))
            Text(alert.detail, style = TypographyTokens.caption, color = colors.textSecondary)
        }
    }
}

@Composable
private fun TodayCardRow(card: TodayCard, onClick: () -> Unit) {
    val colors = MaterialTheme.samaveta
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
    ) {
        Text(
            card.title,
            style = TypographyTokens.bodyEmphasis,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            // Publisher + honest age wording; never a fabricated timestamp.
            card.byline,
            style = TypographyTokens.caption,
            color = colors.textTertiary,
        )
        if (card.limitations.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = colors.caution,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    card.limitations.first(),
                    style = TypographyTokens.caption,
                    color = colors.caution,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        HairlineDivider(inset = 0.dp)
    }
}

@Composable
private fun ActivityRow(item: ActivityListItem) {
    val colors = MaterialTheme.samaveta
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.verified) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule,
            contentDescription = null,
            tint = if (item.verified) colors.success else colors.caution,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(item.action, style = TypographyTokens.body, color = colors.textPrimary, maxLines = 1)
            Text(item.statusText, style = TypographyTokens.caption, color = colors.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun DeviceNoteRow(note: String) {
    val colors = MaterialTheme.samaveta
    Text(
        note,
        style = TypographyTokens.caption,
        color = colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = Spacing.sm),
    )
}

// ---- ViewModel ---------------------------------------------------------------------------------

data class TodayAlert(val title: String, val detail: String, val severity: StatusTone)

data class TodayCard(
    val id: String,
    val title: String,
    val byline: String,
    val limitations: List<String>,
)

data class ActivityListItem(
    val id: String,
    val action: String,
    val statusText: String,
    val verified: Boolean,
)

data class TodayUiState(
    val greeting: String = "",
    val layaState: LayaIntegrationState = LayaIntegrationState.NOT_CONFIGURED,
    val alerts: List<TodayAlert> = emptyList(),
    val topCards: List<TodayCard> = emptyList(),
    val recentActivity: List<ActivityListItem> = emptyList(),
    val deviceNote: String? = null,
) {
    val layaLabel: String get() = layaState.userFacingLabel

    val layaTone: StatusTone
        get() = when (layaState) {
            LayaIntegrationState.VERIFIED -> StatusTone.LIVE
            LayaIntegrationState.NOT_CONFIGURED -> StatusTone.NEUTRAL
            LayaIntegrationState.CONFIGURED_UNVERIFIED,
            LayaIntegrationState.MOCK_TEST_ONLY,
            -> StatusTone.CAUTION
            LayaIntegrationState.DEGRADED -> StatusTone.DANGER
        }
}

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val device: DeviceProfile,
    private val capabilities: DeviceCapabilities,
    @Named("layaState") private val layaState: LayaIntegrationState,
    @Named("clock") private val clock: () -> Long,
) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState(greeting = greetingFor(clock())))
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                layaState = layaState,
                alerts = buildAlerts(),
                deviceNote = buildDeviceNote(),
            )
        }
    }

    /**
     * Alerts are limited to things the user can actually act on. A missing LAYA contract is
     * reported honestly but not as an error, because it is not something the user can fix.
     */
    private fun buildAlerts(): List<TodayAlert> = buildList {
        if (layaState == LayaIntegrationState.MOCK_TEST_ONLY) {
            add(
                TodayAlert(
                    title = "Development build",
                    detail = "The decision engine is a test double. Agent decisions are simulated and must not be relied on.",
                    severity = StatusTone.CAUTION,
                ),
            )
        }
        if (device.memoryTier() == MemoryTier.CONSTRAINED) {
            add(
                TodayAlert(
                    title = "Limited memory",
                    detail = "This device has ${device.totalRamMb} MB RAM. Only small models will be offered, and one at a time.",
                    severity = StatusTone.CAUTION,
                ),
            )
        }
    }

    private fun buildDeviceNote(): String = buildString {
        append("${device.manufacturer} ${device.model} · ${device.totalRamMb} MB RAM · ")
        append("${device.availableStorageMb / 1024} GB free")
        if (capabilities.availableAccelerators().size == 1) {
            append(" · CPU execution only (no verified accelerator)")
        }
    }

    private fun greetingFor(now: Long): String {
        val hour = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).hour
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Late night"
        }
    }
}

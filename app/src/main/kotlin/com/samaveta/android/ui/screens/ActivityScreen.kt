package com.samaveta.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samaveta.android.core.security.ActionStatus
import com.samaveta.android.ui.PageScaffold
import com.samaveta.android.ui.design.EmptyState
import com.samaveta.android.ui.design.Radius
import com.samaveta.android.ui.design.HairlineDivider
import com.samaveta.android.ui.design.Spacing
import com.samaveta.android.ui.design.TypographyTokens
import com.samaveta.android.ui.design.samaveta
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Activity — the audit surface.
 *
 * Every row states the *verification status*, not just "success". A task that was attempted but
 * could not be confirmed is shown as "Unconfirmed" in the caution colour, because telling the user
 * something happened when we do not know is worse than telling them we do not know.
 */
@Composable
fun ActivityScreen(
    bottomInset: Dp,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.samaveta

    PageScaffold(
        title = "Activity",
        subtitle = if (state.entries.isEmpty()) null else "${state.entries.size} recorded action(s)",
    ) {
        if (state.entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.History,
                title = "No activity yet",
                body = "When SAMAVETA runs a task, each step is recorded here with its authorization decision and its verification result. Nothing is recorded until something actually runs.",
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomInset + Spacing.xl),
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    ActivityEntryRow(entry)
                    HairlineDivider()
                }
            }
        }
    }
}

@Composable
private fun ActivityEntryRow(entry: ActivityEntry) {
    val colors = MaterialTheme.samaveta
    val tone = when (entry.status) {
        ActionStatus.SUCCEEDED -> colors.success
        ActionStatus.FAILED -> colors.danger
        ActionStatus.UNCONFIRMED -> colors.caution
        ActionStatus.CANCELLED -> colors.textTertiary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(tone),
            )
            Spacer(Modifier.width(Spacing.md))
            Text(entry.action, style = TypographyTokens.bodyEmphasis, color = colors.textPrimary)
            Spacer(Modifier.weight(1f))
            Text(entry.timeLabel, style = TypographyTokens.caption, color = colors.textTertiary)
        }
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = entry.statusLabel,
            style = TypographyTokens.caption,
            color = tone,
            modifier = Modifier.padding(start = Spacing.lg),
        )
        if (entry.evidence != null) {
            Text(
                text = entry.evidence,
                style = TypographyTokens.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = Spacing.lg),
            )
        }
    }
}

data class ActivityEntry(
    val id: String,
    val action: String,
    val status: ActionStatus,
    val statusLabel: String,
    val evidence: String?,
    val timeLabel: String,
)

data class ActivityUiState(val entries: List<ActivityEntry> = emptyList())

@HiltViewModel
class ActivityViewModel @Inject constructor() : ViewModel() {
    // Populated from the persistent audit log once the database lands; intentionally starts empty
    // rather than showing fabricated history.
    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()
}

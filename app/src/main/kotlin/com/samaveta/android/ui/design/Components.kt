package com.samaveta.android.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared design-system primitives.
 *
 * Rules enforced here rather than per-screen: a single border weight, one corner radius family,
 * one spacing scale, and consistent empty/loading/error presentation. Screens compose these
 * instead of rolling their own, which is what keeps ten features looking like one product.
 */

/** Section heading with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.uppercase(),
            style = TypographyTokens.label,
            color = MaterialTheme.textTertiary,
        )
        action?.invoke()
    }
}


/** A hairline divider: 1px of the border colour, never a full-opacity line. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier, inset: Dp = Spacing.gutter) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(0.7.dp)
            .background(MaterialTheme.samaveta.border),
    )
}

/**
 * Empty state. Always explains *why* it is empty and what the user can do — an empty screen with
 * only a title is a product failure.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.samaveta.textTertiary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = title,
            style = TypographyTokens.heading,
            color = MaterialTheme.samaveta.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = body,
            style = TypographyTokens.body,
            color = MaterialTheme.samaveta.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.md))
            TextButton(onClick = onAction) {
                Text(actionLabel, style = TypographyTokens.bodyEmphasis, color = MaterialTheme.samaveta.accent)
            }
        }
    }
}

/** Error state. Always actionable, never a bare stack trace or an apologetic void. */
@Composable
fun ErrorState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = TypographyTokens.heading, color = MaterialTheme.samaveta.textPrimary)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            body,
            style = TypographyTokens.body,
            color = MaterialTheme.samaveta.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Spacing.md))
            TextButton(onClick = onRetry) {
                Text("Try again", style = TypographyTokens.bodyEmphasis, color = MaterialTheme.samaveta.accent)
            }
        }
    }
}

/** Skeleton block with a slow shimmer. Used only while real content is loading. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.samaveta.skeleton.copy(alpha = alpha)),
    )
}

/**
 * Status pill. Used for model state, LAYA state, verification results.
 * Colour is semantic: accent = live/active, caution = degraded/unverified, danger = failed.
 */
@Composable
fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.samaveta
    val (fg, bg) = when (tone) {
        StatusTone.LIVE -> colors.accent to colors.accentContainer
        StatusTone.NEUTRAL -> colors.textSecondary to colors.surfaceElevated
        StatusTone.CAUTION -> colors.caution to colors.surfaceElevated
        StatusTone.DANGER -> colors.danger to colors.surfaceElevated
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(bg)
            .border(0.7.dp, colors.border, RoundedCornerShape(Radius.pill))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tone == StatusTone.LIVE) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(colors.liveIndicator))
            Spacer(Modifier.width(Spacing.xs + 1.dp))
        }
        Text(label, style = TypographyTokens.label, color = fg)
    }
}

enum class StatusTone { LIVE, NEUTRAL, CAUTION, DANGER }

/**
 * A plain, bordered container. Cards are used sparingly in SAMAVETA — most content sits on the
 * canvas with a hairline separator — but this is the one container primitive for grouped settings
 * and detail blocks.
 */
@Composable
fun SurfaceBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.samaveta
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.surface)
            .border(0.7.dp, colors.border, RoundedCornerShape(Radius.md))
            .padding(Spacing.lg),
        content = content,
    )
}

/** Accessible icon button wrapper: guarantees a 48dp touch target and a content description. */
@Composable
fun AccessibleIcon(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.samaveta.textSecondary,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = modifier
            .size(24.dp)
            .semantics { contentDescription = description },
    )
}

package com.jay.glossy.ui.player

import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * The status bar height the player lays itself out against.
 *
 * Fullscreen hides the status bar, and padding that follows the live inset
 * moved the whole player by that height. The sheet animates open and closed,
 * so the artwork and controls jerked up and down with it. The last measured
 * height is kept while the bar is hidden: the layout stays put, and the strip
 * it leaves is the player's own background.
 */
@Composable
fun rememberStableStatusBarHeight(): Dp {
    val density = LocalDensity.current
    val context = LocalContext.current
    val memory = remember(density) { StatusBarHeightMemory(statusBarHeightPx(context)) }
    val measuredPx = WindowInsets.statusBars.getTop(density)
    if (measuredPx > 0) memory.heightPx = measuredPx
    return with(density) { memory.heightPx.toDp() }
}

/** Top padding equal to the status bar height, unaffected by whether the bar is shown. */
@Composable
fun Modifier.stableStatusBarsPadding(): Modifier = padding(top = rememberStableStatusBarHeight())

private class StatusBarHeightMemory(var heightPx: Int)

private fun statusBarHeightPx(context: Context): Int {
    val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
}

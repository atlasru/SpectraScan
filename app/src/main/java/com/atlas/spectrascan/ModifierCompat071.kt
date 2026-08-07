package com.atlas.spectrascan

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Compatibility helper for the standalone settings composable in 0.7.1.
 * BoxScope.align is used normally inside Box content; this fallback is only
 * selected where no BoxScope receiver exists. It keeps the settings panel
 * anchored to the right edge of the screen.
 */
internal fun Modifier.align(alignment: Alignment): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.End)

package com.repl.bubbledrawer.ui.component

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.sqrt

/**
 * Spring spec shared by pager tab navigation.
 */
internal val PagerNavigationSpringSpec: SpringSpec<Float> = spring(
    stiffness = 322.2f,
    dampingRatio = 32.31f / (2f * sqrt(322.2f)),
    visibilityThreshold = 0.5f,
)

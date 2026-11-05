package com.repl.bubbledrawer.ui.screen.features

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.pin.PinManageActivity
import com.repl.bubbledrawer.settings.ArrowRow
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.PageScaffold

/**
 * Features hub — categorizes all module capabilities into distinct dedicated screens
 * following the HyperMusicCover architecture.
 */
@Composable
fun FeaturesPageView(
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current

    PageScaffold(
        title = stringResource(R.string.tab_features),
        extraBottomPadding = extraBottomPadding,
    ) {
        item(key = "lead") { LeadSpacer() }

        groupedCardItems(
            keyPrefix = "features_list",
            items = listOf(
                CardItem("trigger") {
                    ArrowRow(
                        title = stringResource(R.string.features_trigger_title),
                        summary = stringResource(R.string.features_trigger_summary),
                        onClick = {
                            context.startActivity(Intent(context, TriggerActivity::class.java))
                        },
                    )
                },
                CardItem("fan") {
                    ArrowRow(
                        title = stringResource(R.string.features_fan_title),
                        summary = stringResource(R.string.features_fan_summary),
                        onClick = {
                            context.startActivity(Intent(context, FanDockActivity::class.java))
                        },
                    )
                },
                CardItem("panel") {
                    ArrowRow(
                        title = stringResource(R.string.features_panel_title),
                        summary = stringResource(R.string.features_panel_summary),
                        onClick = {
                            context.startActivity(Intent(context, MorePanelActivity::class.java))
                        },
                    )
                },
                CardItem("manage") {
                    ArrowRow(
                        title = stringResource(R.string.features_manage_title),
                        summary = stringResource(R.string.features_manage_summary),
                        onClick = {
                            context.startActivity(Intent(context, PinManageActivity::class.java))
                        },
                    )
                },
                CardItem("light_open") {
                    ArrowRow(
                        title = stringResource(R.string.features_light_open_title),
                        summary = stringResource(R.string.features_light_open_summary),
                        onClick = {
                            context.startActivity(Intent(context, LightOpenActivity::class.java))
                        },
                    )
                },
                CardItem("freeform") {
                    ArrowRow(
                        title = stringResource(R.string.features_freeform_title),
                        summary = stringResource(R.string.features_freeform_summary),
                        onClick = {
                            context.startActivity(Intent(context, FreeformActivity::class.java))
                        },
                    )
                },
                CardItem("freeform_extend") {
                    ArrowRow(
                        title = stringResource(R.string.features_freeform_extend_title),
                        summary = stringResource(R.string.features_freeform_extend_summary),
                        onClick = {
                            context.startActivity(Intent(context, FreeformExtendActivity::class.java))
                        },
                    )
                },
                CardItem("freeform_other") {
                    ArrowRow(
                        title = stringResource(R.string.features_freeform_other_title),
                        summary = stringResource(R.string.features_freeform_other_summary),
                        onClick = {
                            context.startActivity(Intent(context, FreeformOtherActivity::class.java))
                        },
                    )
                },
            ),
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}

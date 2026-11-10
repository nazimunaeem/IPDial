package com.ipdial.ui.screens

import androidx.compose.runtime.Composable
import com.ipdial.ui.SipViewModel

// GetProScreen deprecated / removed as OAuth and points are dropped.
@Composable
fun GetProScreen(
    vm: SipViewModel,
    onBack: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null
) {
    onBack()
}

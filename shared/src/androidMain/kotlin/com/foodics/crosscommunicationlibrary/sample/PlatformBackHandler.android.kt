package com.foodics.crosscommunicationlibrary.sample

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformBackHandler(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
}

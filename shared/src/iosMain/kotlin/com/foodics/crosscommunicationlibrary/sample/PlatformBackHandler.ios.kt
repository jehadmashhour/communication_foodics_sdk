package com.foodics.crosscommunicationlibrary.sample

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformBackHandler(onBack: () -> Unit) { /* no-op on iOS */ }

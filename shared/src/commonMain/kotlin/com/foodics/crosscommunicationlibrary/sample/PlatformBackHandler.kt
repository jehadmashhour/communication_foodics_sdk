package com.foodics.crosscommunicationlibrary.sample

import androidx.compose.runtime.Composable

@Composable
internal expect fun PlatformBackHandler(onBack: () -> Unit)

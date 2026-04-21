package com.foodics.crosscommunicationlibrary.sample

import com.foodics.crosscommunicationlibrary.logger.DatadogConfig
import kotlin.native.Platform

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual fun sampleDatadogConfig(): DatadogConfig = DatadogConfig(
    clientToken = "pub4f51a761a700a9caf8c15aa221bc4dd4",
    env = "dev",
    variant = if (Platform.isDebugBinary) "debug" else "release",
    service = "CrossCommunicationLibrary-IOS"
)

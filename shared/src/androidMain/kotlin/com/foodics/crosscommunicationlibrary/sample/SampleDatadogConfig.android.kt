package com.foodics.crosscommunicationlibrary.sample

import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import com.foodics.crosscommunicationlibrary.BuildConfig
import com.foodics.crosscommunicationlibrary.logger.DatadogConfig

internal actual fun sampleDatadogConfig(): DatadogConfig = DatadogConfig(
    clientToken = "pub4f51a761a700a9caf8c15aa221bc4dd4",
    env = "dev",
    variant = if (BuildConfig.DEBUG) "debug" else "release",
    service = "CrossCommunicationLibrary-Android",
    platformContext = AndroidAppContextProvider.context
)

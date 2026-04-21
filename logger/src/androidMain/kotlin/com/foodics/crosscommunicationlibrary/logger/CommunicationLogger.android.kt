package com.foodics.crosscommunicationlibrary.logger

import android.content.Context
import com.benasher44.uuid.uuid4
import com.datadog.android.Datadog
import com.datadog.android.core.configuration.Configuration
import com.datadog.android.log.Logger
import com.datadog.android.log.Logs
import com.datadog.android.log.LogsConfiguration
import com.datadog.android.privacy.TrackingConsent

actual class CommunicationLogger actual constructor(
    datadogConfig: DatadogConfig,
    private val attributes: LogAttributes
) {
    private val ddLogger: Logger

    init {
        if (!isInitialized) {
            val context = requireNotNull(datadogConfig.platformContext as? Context) {
                "DatadogConfig.platformContext must be an Android Context. Pass your Application context."
            }
            val config = Configuration.Builder(
                clientToken = datadogConfig.clientToken,
                env = datadogConfig.env,
                variant = datadogConfig.variant,
                service = datadogConfig.service
            ).build()
            Datadog.initialize(context, config, TrackingConsent.GRANTED)
            Logs.enable(LogsConfiguration.Builder().build())
            isInitialized = true
        }

        ddLogger = Logger.Builder()
            .setName("CrossCommunicationLibrary")
            .setNetworkInfoEnabled(true)
            .setLogcatLogsEnabled(true)
            .build()

        attributes.toMap().forEach { (key, value) ->
            if (value != null) ddLogger.addAttribute(key, value.toString())
        }
    }

    actual fun log(
        level: LogLevel,
        title: String,
        message: String,
        throwable: Throwable?,
        extra: Map<String, Any?>
    ) {
        val perLogAttrs: Map<String, Any?> = buildMap {
            put(ATTRIBUTE_LOG_ID, uuid4().toString())
            put(ATTRIBUTE_LOG_TITLE, "$title | $message")
            put(ATTRIBUTE_LOG_TYPE, level.name)
            putAll(extra)
        }
        when (level) {
            LogLevel.DEBUG -> ddLogger.d(message, throwable, perLogAttrs)
            LogLevel.INFO  -> ddLogger.i(message, throwable, perLogAttrs)
            LogLevel.WARN  -> ddLogger.w(message, throwable, perLogAttrs)
            LogLevel.ERROR -> ddLogger.e(message, throwable, perLogAttrs)
        }
    }

    companion object {
        @Volatile private var isInitialized = false
    }
}

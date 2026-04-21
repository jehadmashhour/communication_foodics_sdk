package com.foodics.crosscommunicationlibrary.logger

import com.benasher44.uuid.uuid4
import platform.Foundation.NSLog

actual class CommunicationLogger actual constructor(
    datadogConfig: DatadogConfig,
    private val attributes: LogAttributes
) {
    private val baseAttrs: Map<String, Any?> = attributes.toMap()

    init {
        DatadogBridge.datadogConfig = datadogConfig
    }

    actual fun log(
        level: LogLevel,
        title: String,
        message: String,
        throwable: Throwable?,
        extra: Map<String, Any?>
    ) {
        val allAttrs: Map<String, Any?> = buildMap {
            putAll(baseAttrs)
            put(ATTRIBUTE_LOG_ID, uuid4().toString())
            put(ATTRIBUTE_LOG_TITLE, title)
            put(ATTRIBUTE_LOG_TYPE, level.name)
            putAll(extra)
            throwable?.let { put("error_message", it.message ?: "Unknown error") }
        }

        val sink = DatadogBridge.logSink
        if (sink != null) {
            sink(level.name, message, allAttrs)
        } else {
            val attrsStr = allAttrs.entries.joinToString(", ") { "${it.key}=${it.value}" }
            NSLog("[CrossCommunicationLibrary][%@][%@] %@ | %@", level.name, title, message, attrsStr)
        }
    }
}

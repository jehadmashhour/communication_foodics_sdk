package com.foodics.crosscommunicationlibrary.logger

expect class CommunicationLogger(
    datadogConfig: DatadogConfig,
    attributes: LogAttributes
) {
    fun log(
        level: LogLevel,
        title: String,
        message: String,
        throwable: Throwable? = null,
        extra: Map<String, Any?> = emptyMap()
    )
}

fun CommunicationLogger.debug(title: String, message: String, extra: Map<String, Any?> = emptyMap()) =
    log(LogLevel.DEBUG, title, message, null, extra)

fun CommunicationLogger.info(title: String, message: String, extra: Map<String, Any?> = emptyMap()) =
    log(LogLevel.INFO, title, message, null, extra)

fun CommunicationLogger.warn(title: String, message: String, extra: Map<String, Any?> = emptyMap()) =
    log(LogLevel.WARN, title, message, null, extra)

fun CommunicationLogger.error(title: String, message: String, throwable: Throwable? = null, extra: Map<String, Any?> = emptyMap()) =
    log(LogLevel.ERROR, title, message, throwable, extra)

package com.foodics.crosscommunicationlibrary.logger

/**
 * iOS bridge for Datadog integration.
 *
 * ## Setup (Swift side)
 *
 * 1. After calling `CommunicationSDK.builder().enableLogging(...).build()`, read the config
 *    from this bridge and initialize Datadog, then set the logSink:
 *
 * ```swift
 * let cfg = DatadogBridge.shared.datadogConfig!
 * Datadog.initialize(
 *     with: Datadog.Configuration(clientToken: cfg.clientToken, env: cfg.env,
 *                                  service: cfg.service, variant: cfg.variant),
 *     trackingConsent: .granted
 * )
 * Logs.enable()
 *
 * let logger = Logger.create(with: Logger.Configuration(name: "CrossCommunicationLibrary",
 *                                                       networkInfoEnabled: true))
 * DatadogBridge.shared.logSink = { level, message, attributes in
 *     let encodable = attributes.compactMapValues { $0 as? Encodable }
 *     switch level {
 *     case "DEBUG": logger.debug(message, attributes: encodable)
 *     case "INFO":  logger.info(message,  attributes: encodable)
 *     case "WARN":  logger.warn(message,  attributes: encodable)
 *     default:      logger.error(message, attributes: encodable)
 *     }
 * }
 * ```
 *
 * If [logSink] is not set the SDK falls back to NSLog.
 */
object DatadogBridge {
    /** Populated automatically when [CommunicationLogger] is constructed. Read from Swift to initialize Datadog. */
    var datadogConfig: DatadogConfig? = null

    /** Set from Swift after Datadog.initialize() to forward logs through your DDLogger. */
    var logSink: ((level: String, message: String, attributes: Map<String, Any?>) -> Unit)? = null
}

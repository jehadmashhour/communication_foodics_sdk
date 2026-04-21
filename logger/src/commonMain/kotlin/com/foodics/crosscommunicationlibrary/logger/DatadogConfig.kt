package com.foodics.crosscommunicationlibrary.logger

/**
 * Datadog SDK initialization parameters.
 *
 * @param clientToken      Datadog client token (required).
 * @param env              Deployment environment, e.g. "production" or "staging".
 * @param variant          Build variant, e.g. "release". Defaults to empty string (no variant).
 * @param service          Service name override. Defaults to "CrossCommunicationLibrary".
 * @param platformContext  Android: pass your Application/Activity Context here.
 *                         iOS: leave null — Datadog is initialized from Swift via [DatadogBridge].
 */
data class DatadogConfig(
    val clientToken: String,
    val env: String,
    val variant: String = "",
    val service: String = "CrossCommunicationLibrary",
    val platformContext: Any? = null
)

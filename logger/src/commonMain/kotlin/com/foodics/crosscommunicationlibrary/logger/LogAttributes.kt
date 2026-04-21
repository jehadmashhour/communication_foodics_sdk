package com.foodics.crosscommunicationlibrary.logger

/**
 * User-provided attributes attached to every log entry.
 *
 * [platformDeviceId] maps to "android_id" on Android and "ios_vendor_id" on iOS.
 * [extra] carries any additional key-value pairs not covered by the named fields.
 */
data class LogAttributes(
    val businessName: String = "",
    val businessReference: String = "",
    val deviceVersion: String = "",
    val deviceBuild: String = "",
    val deviceId: String = "",
    val iminSerialNumber: String? = null,
    val platformDeviceId: String? = null,
    val branchId: String? = null,
    val branchName: String? = null,
    val branchReference: String? = null,
    val extra: Map<String, Any?> = emptyMap()
)

fun LogAttributes.toMap(): Map<String, Any?> = buildMap {
    put(ATTRIBUTE_BUSINESS_NAME, businessName)
    put(ATTRIBUTE_BUSINESS_REFERENCE, businessReference)
    put(ATTRIBUTE_DEVICE_VERSION, deviceVersion)
    put(ATTRIBUTE_DEVICE_BUILD, deviceBuild)
    put(ATTRIBUTE_DEVICE_ID, deviceId)
    iminSerialNumber?.let { put(ATTRIBUTE_IMIN_SN, it) }
    platformDeviceId?.let { put(ATTRIBUTE_PLATFORM_DEVICE_ID, it) }
    branchId?.let { put(ATTRIBUTE_BRANCH_ID, it) }
    branchName?.let { put(ATTRIBUTE_BRANCH_NAME, it) }
    branchReference?.let { put(ATTRIBUTE_BRANCH_REFERENCE, it) }
    putAll(extra)
}

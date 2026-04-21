package com.foodics.crosscommunicationlibrary.logger

const val ATTRIBUTE_BUSINESS_NAME = "business_name"
const val ATTRIBUTE_BUSINESS_REFERENCE = "business_reference"
const val ATTRIBUTE_DEVICE_VERSION = "device_version"
const val ATTRIBUTE_DEVICE_BUILD = "device_build"
const val ATTRIBUTE_DEVICE_ID = "device_id"
const val ATTRIBUTE_IMIN_SN = "imin_serial_number"
const val ATTRIBUTE_LOG_ID = "log_id"
const val ATTRIBUTE_LOG_TITLE = "log_title"
const val ATTRIBUTE_LOG_TYPE = "log_type"
const val ATTRIBUTE_BRANCH_ID = "branch_id"
const val ATTRIBUTE_BRANCH_NAME = "branch_name"
const val ATTRIBUTE_BRANCH_REFERENCE = "branch_reference"
const val ATTRIBUTE_CONNECTION_TYPE = "connection_type"

/**
 * Key for the platform-specific device identifier.
 * The value passed by the SDK user should be "android_id" on Android
 * and the "identifierForVendor" UUID on iOS.
 */
const val ATTRIBUTE_PLATFORM_DEVICE_ID = "platform_device_id"

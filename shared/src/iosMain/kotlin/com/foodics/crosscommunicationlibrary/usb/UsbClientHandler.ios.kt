package com.foodics.crosscommunicationlibrary.usb


/**
 * iOS does not support USB Host mode for third-party apps.
 * Communicating with USB peripherals requires Apple MFi certification for the
 * accessory hardware and entitlements not available to general App Store apps.
 *
 * Scan always returns an empty list. All other methods are no-ops.
 */
actual class UsbClientHandler
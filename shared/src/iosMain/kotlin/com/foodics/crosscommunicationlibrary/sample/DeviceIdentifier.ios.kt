package com.foodics.crosscommunicationlibrary.sample

import platform.UIKit.UIDevice

actual fun deviceIdentifier(): String =
    UIDevice.currentDevice.identifierForVendor?.UUIDString
        ?: UIDevice.currentDevice.name

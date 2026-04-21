package com.foodics.crosscommunicationlibrary.sample

import android.provider.Settings
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider

actual fun deviceIdentifier(): String =
    Settings.Secure.getString(
        AndroidAppContextProvider.context.contentResolver,
        Settings.Secure.ANDROID_ID
    )

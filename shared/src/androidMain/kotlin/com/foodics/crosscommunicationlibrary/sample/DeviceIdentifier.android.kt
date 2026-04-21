package com.foodics.crosscommunicationlibrary.sample

import android.provider.Settings
import com.foodics.crosscommunicationlibrary.AppContext

actual fun deviceIdentifier(): String =
    Settings.Secure.getString(
        AppContext.get().contentResolver,
        Settings.Secure.ANDROID_ID
    )

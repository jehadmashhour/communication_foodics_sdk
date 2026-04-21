package com.foodics.androidsample

import android.app.Application
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidAppContextProvider.context = this
    }
}

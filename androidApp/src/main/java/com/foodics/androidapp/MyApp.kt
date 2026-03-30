package com.foodics.androidapp

import android.app.Application
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        AndroidAppContextProvider.context = this
    }
}

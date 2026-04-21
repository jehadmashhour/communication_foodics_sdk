package com.foodics.crosscommunicationlibrary

import android.content.Context

internal object AppContext {
    private lateinit var appContext: Context

    internal fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context = appContext
}

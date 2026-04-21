package com.foodics.crosscommunicationlibrary

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

internal class CrossCommunicationInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        AppContext.init(context!!)
        return true
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, sA: Array<String>?, so: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sA: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sA: Array<String>?): Int = 0
}

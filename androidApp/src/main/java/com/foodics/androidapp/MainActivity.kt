package com.foodics.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.foodics.androidapp.ui.theme.CrossCommunicationLibraryTheme
import com.foodics.crosscommunicationlibrary.ui.MainView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CrossCommunicationLibraryTheme {
                MainView(this@MainActivity)
            }
        }
    }
}

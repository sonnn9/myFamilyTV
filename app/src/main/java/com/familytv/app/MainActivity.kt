package com.familytv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.familytv.app.ui.MainRoot
import com.familytv.app.ui.theme.FamilyTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = appContainer
        setContent {
            FamilyTvTheme {
                MainRoot(container, this)
            }
        }
    }
}

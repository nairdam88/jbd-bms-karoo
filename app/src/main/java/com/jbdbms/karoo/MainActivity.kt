package com.jbdbms.karoo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.jbdbms.karoo.data.BmsRepository
import com.jbdbms.karoo.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BmsRepository.init(applicationContext)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        BmsRepository.setUiVisible(true)
    }

    override fun onStop() {
        BmsRepository.setUiVisible(false)
        super.onStop()
    }
}

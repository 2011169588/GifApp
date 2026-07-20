package com.example.gifapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gifapp.ui.screens.EditorScreen
import com.example.gifapp.ui.theme.GifAppTheme
import com.example.gifapp.viewmodel.EditorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GifAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: EditorViewModel = viewModel()
                    EditorScreen(viewModel = viewModel)
                }
            }
        }
    }
}

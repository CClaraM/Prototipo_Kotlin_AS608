package com.example.miappcompose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.miappcompose.hardware.AS608Helper
import com.example.miappcompose.ui.AS608Screen
import com.example.miappcompose.ui.theme.MiAppComposeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MiAppComposeTheme {
                val context = LocalContext.current
                val helper = remember { AS608Helper(context) }
                AS608Screen(helper)
            }
        }
    }
}

@Composable
fun AS608Screen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val helper = remember { AS608Helper(context) }
    var status by remember { mutableStateOf("Esperando conexión...") }

    Scaffold(topBar = {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(
                modifier = Modifier
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Lector de huellas AS608",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

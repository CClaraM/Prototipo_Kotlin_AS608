package com.example.miappcompose.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.miappcompose.hardware.AS608Helper

@Composable
fun StableTopBar(title: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
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
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
fun AS608Screen(helper: AS608Helper) {
    var status by remember { mutableStateOf("Esperando...") }
    var fingerprint by remember { mutableStateOf<Bitmap?>(null) }

    Scaffold(
        topBar = {
            StableTopBar(title = "Control de lector AS608")
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = status, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))

            fingerprint?.let { bmp ->
                val scaledBmp = Bitmap.createScaledBitmap(bmp, 256, 288, true)
                Image(
                    bitmap = scaledBmp.asImageBitmap(),
                    contentDescription = "Huella capturada",
                    modifier = Modifier
                        .width(256.dp)
                        .height(288.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = { helper.genImgWithResponse() }) { Text("Capturar") }
                Button(onClick = { helper.img2TzWithResponse() }) { Text("Convertir") }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = { helper.regModelWithResponse() }) { Text("Modelo") }
                Button(onClick = { helper.storeWithResponse(1) }) { Text("Guardar") }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = { helper.searchWithResponse() }) { Text("Buscar") }
                Button(onClick = { helper.emptyWithResponse() }) { Text("Borrar todo") }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                helper.verifyFinger { ok ->
                    if (ok) {
                        helper.getImageClean()
                    } else {
                        // helper.onStatus?.invoke("⚠️ No hay huella detectada. No se iniciará la lectura de imagen.")
                        return@verifyFinger
                    }
                }
            }) {
                Text("Ver imagen")
            }
        }
    }

    // ✅ Iniciar y detener helper correctamente
    DisposableEffect(Unit) {
        helper.start(
            onStatus = { msg -> status = msg },
            onImage = { bmp -> fingerprint = bmp }
        )
        onDispose { helper.stop() }
    }
}

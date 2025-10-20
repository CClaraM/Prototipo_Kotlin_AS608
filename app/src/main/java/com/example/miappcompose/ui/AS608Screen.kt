package com.example.miappcompose.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.miappcompose.hardware.AS608Helper
import com.example.miappcompose.sdk.SDKResult

@Composable
fun StableTopBar(
    title: String,
    modifier: Modifier = Modifier
) {
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
    var status by remember { mutableStateOf("Listo") }
    var fingerprint by remember { mutableStateOf<Bitmap?>(null) }
    var sysParams by remember { mutableStateOf("") }
    var queryId by remember { mutableStateOf("1") }
    var base64Template by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    Scaffold(topBar = { StableTopBar("AS608 SDK Demo") }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = status, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))

            if (sysParams.isNotBlank()) {
                Text(sysParams, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            fingerprint?.let { bmp ->
                val scaled = Bitmap.createScaledBitmap(bmp, 256, 288, true)
                Image(
                    bitmap = scaled.asImageBitmap(),
                    contentDescription = "Huella",
                    modifier = Modifier.size(width = 256.dp, height = 288.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    helper.getParameters {
                        status = if (it.success) "✅ Parámetros OK" else "❌ ${it.message}"
                        if (it.success) sysParams = it.data ?: ""
                    }
                }) { Text("Parámetros") }

                Button(onClick = {
                    helper.getImage {
                        status = if (it.success) "✅ Imagen OK" else "❌ ${it.message}"
                        if (it.success) fingerprint = it.data
                    }
                }) { Text("Ver imagen") }
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    helper.genImg { res ->
                        status = if (res.success) "✅ Capturada" else "❌ ${res.message}"
                    }
                }) { Text("Capturar") }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = queryId,
                onValueChange = { queryId = it },
                label = { Text("ID o Buffer") },
                modifier = Modifier.width(120.dp)
            )

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                // 📸 Capturar y convertir a buffer
                Button(onClick = {
                    val id = queryId.toIntOrNull() ?: 1
                    helper.captureAndConvert(id) { success, code, msg ->
                        status = msg
                    }
                }) {
                    Text("📸 Capturar Buffer")
                }

                // 🧠 Combinar buffers en RAM
                Button(onClick = {
                    helper.registerModel { success, code, msg ->
                        status = msg
                    }
                }) {
                    Text("🧠 Combinar Buffers")
                }

            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    helper.downloadRam(1) { result ->
                        status = result.message
                        if (result.success && result.data != null) {
                            base64Template = result.data
                        }
                    }
                }) {
                    Text("📥 Descargar RAM")
                }

                Button(onClick = {
                    helper.uploadRam(base64Template, 1) { result ->
                        status = result.message
                    }
                }) {
                    Text("📤 Cargar RAM")
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    val id = queryId.toIntOrNull() ?: 1
                    helper.downloadTemplateFromId(id, 1) { result ->
                        status = result.message
                        if (result.success && result.data != null) {
                            base64Template = result.data
                        }
                    }
                }) {
                    Text("📥 Descargar ID")
                }
                Button(onClick = {
                    val id = queryId.toIntOrNull() ?: 1
                    if (base64Template.isNotEmpty()) {
                        helper.uploadTemplateToId(base64Template, id) { result ->
                            status = result.message
                        }
                    } else {
                        status = "⚠️ No hay template Base64 cargado desde el servidor"
                    }
                }) {
                    Text("☁️ Subir → ID")
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    val id = queryId.toIntOrNull() ?: 1
                    helper.storeTemplateAtId(id) { success, code, msg ->
                        status = msg
                    }
                }) { Text("Guardar ID") }

                Button(onClick = {
                    val id = queryId.toIntOrNull() ?: 1
                    helper.deleteTemplateWithResponse(id) { success, code, msg ->
                        status = msg
                    }
                }) { Text("Borrar ID") }
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    helper.search { res: SDKResult<Pair<Int,Int>> ->
                        status = if (res.success) "✅ Match ID=${res.data?.first} Score=${res.data?.second}"
                        else "❌ ${res.message}"
                    }
                }) { Text("Buscar") }

                Button(onClick = {
                    helper.empty { res ->
                        status = if (res.success) "🧹 Base vaciada" else "❌ ${res.message}"
                    }
                }) { Text("Borrar todo") }
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    helper.readIndexAll { res ->
                        status = if (res.success) "📊 IDs: ${res.data}" else "❌ ${res.message}"
                    }
                }) { Text("Listar IDs") }

                Button(onClick = {
                    helper.verifyPassword(0x00000000u) { res ->
                        status = if (res.success) "🔓 Password OK" else "🔒 ${res.message}"
                    }
                }) { Text("Verify Pass 0") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    helper.setPassword(12340000u) { result ->
                        status = result.message
                    }
                }) {
                    Text("🔐 Set Password")
                }
                Button(onClick = {
                    helper.setSecurityLevel(3) { result ->
                        status = result.message
                    }
                }) {
                    Text("🧱 Security Lvl 3")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    helper.verifyPassword(0x12340000u) { res ->
                        status = if (res.success) "🔓 Password OK" else "🔒 ${res.message}"
                    }
                }) { Text("Verify Pass 12340000") }
                Button(onClick = {
                    helper.setSecurityLevel(1) { result ->
                        status = result.message
                    }
                }) {
                    Text("🧱 Security Lvl 1")
                }
            }

        }
    }

    DisposableEffect(Unit) {
        helper.start(
            onStatus = { msg -> status = msg },
            onImage = { bmp -> fingerprint = bmp }
        )
        onDispose { helper.stop() }
    }
}

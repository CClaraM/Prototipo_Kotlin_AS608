package com.example.miappcompose.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.example.miappcompose.hardware.AS608Helper
import com.example.miappcompose.hardware.AS608Protocol

//import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import android.util.Log

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
    var sysParams by remember { mutableStateOf("") }
    var fingerCount by remember { mutableStateOf<Int?>(null) }

    var templateIdText by remember { mutableStateOf("1") }

    var base64Template by remember { mutableStateOf("") }
    //var base64Template by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    // 📝 Lista de logs
    val logList = remember { mutableStateListOf<String>() }

    fun addLog(entry: String) {
        // guardamos máximo 50 eventos para evitar que se haga enorme
        if (logList.isNotEmpty() && logList.size >= 50) {
            logList.removeAt(0)
        }
        logList.add("🕒 ${System.currentTimeMillis() % 100000}: $entry")
    }

    Scaffold(
        topBar = { StableTopBar(title = "Panel de control AS608") }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState) // 👈 Habilita scroll vertical
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // 📡 Estado general
            Text(text = status, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))

            // 🖼️ Vista de huella
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

            // 📊 Información de parámetros y conteo
            if (sysParams.isNotEmpty()) {
                Text(
                    text = sysParams,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            fingerCount?.let {
                Text(
                    text = "📇 Huellas almacenadas: $it",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
            }

            // ==========================================================
            // 📸 1. Captura y gestión de imagen
            // ==========================================================
            Text("📸 Imagen", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                helper.verifyFinger { ok ->
                    if (ok) helper.getImage()
                }
            }) { Text("Ver imagen") }

            Spacer(Modifier.height(8.dp))
            TextField(
                value = templateIdText,
                onValueChange = { templateIdText = it.filter { c -> c.isDigit() } },
                label = { Text("ID para guardar") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = { helper.genImgWithResponse() }) { Text("Capturar") }
                Button(onClick = { helper.convertWithResponse() }) { Text("Convertir") }
            }
            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        val id = templateIdText.toIntOrNull()
                        if (id != null) {
                            helper.storeTemplateWithResponse(id)
                        } else {
                            // si el usuario no pone un número válido
                            helper.onStatus?.invoke("⚠️ ID inválido")
                        }
                    }
                ) {
                    Text("Guardar ID")
                }

                Button(onClick = { helper.searchWithResponse() }) { Text("Buscar") }
            }

            Spacer(Modifier.height(16.dp))

            // ==========================================================
            // 🔍 2. Búsqueda y base interna
            // ==========================================================
            Text("🔍 Base interna", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = {
                    val id = templateIdText.toIntOrNull()
                    if (id != null) {
                        helper.deleteTemplateWithResponse(id)
                    } else {
                        // si el usuario no pone un número válido
                        helper.onStatus?.invoke("⚠️ ID inválido")
                    }
                }) { Text("Borrar ID") }

                Button(onClick = {
                    helper.deleteAllFingerprints {
                        status = if (it) "🧹 Huellas borradas correctamente" else "❌ Error al borrar huellas"
                    }
                }) {
                    Text("🧼 Borrar todo")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ){
                Button(onClick = {
                    val id = templateIdText.toIntOrNull()
                    if (id != null && id < 2) {
                        helper.getFingerprintInfo(id) { count, ids ->
                            status = if (count > 0) {
                                "📊 Huellas registradas: $count\n🆔 IDs ocupados: $ids"
                            } else {
                                "⚠️ No hay huellas registradas"
                            }
                        }
                    } else {
                        // si el usuario no pone un número válido
                        helper.onStatus?.invoke("⚠️ ID inválido")
                    }
                }) { Text("📋 Leer pagina IDs") }

                Button(onClick = {
                    helper.getAllFingerprintInfo { total, ids ->
                        status = "📊 Total: $total huellas\n🆔 IDs: $ids"
                    }
                }) {
                    Text("📋 Leer All IDs")
                }
            }

            Spacer(Modifier.height(8.dp))

            // ==========================================================
            // 🧾 3. Información del lector
            // ==========================================================
            Text("🧾 Información del lector", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = { helper.readSysParameters {params -> sysParams = params} }) { Text("Leer parámetros") }
                Button(onClick = { helper.cancelWithResponse() }) { Text("Cancelar") }
            }

            Spacer(Modifier.height(8.dp))

            // ==========================================================
            // 🧬 4. Templates
            // ==========================================================
            Text("🧬 Templates", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = {

                    val id = templateIdText.toIntOrNull()
                    if (id != null) {
                        helper.downloadTemplateBase64FromId(
                            pageId = 1,
                            bufferId = id
                        ) { base64 ->
                            if (base64 != null) {
                                base64Template = base64
                                status = "📥 Template ID 1 descargado correctamente (${base64.length} chars)"
                            } else {
                                status = "❌ Error al descargar template desde ID"
                            }
                        }
                    } else {
                        // si el usuario no pone un número válido
                        helper.onStatus?.invoke("⚠️ ID inválido")
                    }

                }) {
                    Text("📥 Descargar ID")
                }


                Button(onClick = {
                    if (base64Template.isNotEmpty()) {
                        val id = templateIdText.toIntOrNull()
                        if (id != null) {
                            helper.uploadTemplateBase64(base64Template, id)
                        } else {
                            // si el usuario no pone un número válido
                            helper.onStatus?.invoke("⚠️ ID inválido")
                        }
                    } else {
                        helper.onStatus?.invoke("⚠️ No hay template en memoria local")
                    }
                }) { Text("📤 Subir ID") }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = {
                    helper.downloadRam(
                        bufferId = 1,
                        base64 = { tplBase64 ->
                            if (tplBase64 != null) {
                                // ✅ Guardamos el template descargado en variable global o de estado
                                base64Template = tplBase64
                                status = "📥 Template RAM descargado correctamente (${tplBase64.length} chars)"
                            } else {
                                status = "❌ Error al descargar template desde RAM"
                            }
                        },
                        onDone = { ok ->
                            if (ok) {
                                Log.d("BTN_RAM", "✅ Descarga completada correctamente")
                            } else {
                                Log.d("BTN_RAM", "⚠️ Falló la descarga")
                            }
                        }
                    )
                }) {
                    Text("📥 Descargar RAM")
                }

                Button(onClick = {
                    if (base64Template.isNotEmpty()) {
                        helper.uploadRam(
                            base64 = base64Template,
                            bufferId = 1, // RAM, no se guarda en ID
                            onDone = { ok ->
                                status = if (ok) "✅ Template subido" else "❌ Fallo al subir"
                            }
                        )
                    } else {
                        status = "⚠️ No hay template cargado en memoria"
                    }
                }) {
                    Text("📤 Subir a RAM")
                }
            }

            Spacer(Modifier.height(8.dp))

            // ==========================================================
            // 🧬 5. Password
            // ==========================================================
            Text("🔐 Seguridad", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ){
                // Establecer contraseña (ej. 123456)
                Button(onClick = {
                    val pwd: UInt = 123456u
                    helper.setPassword(pwd) { ok ->
                        if (ok) {
                            helper.writeSysParameters { saved ->
                                if (saved) {
                                    status = "✅ Contraseña establecida y guardada"
                                } else {status = "❌ Error al guardar contraseña"}
                            }
                        } else { status = "❌ Error al establecer contraseña" }
                    }
                }) {
                    Text("🔐 Set Password")
                }

                // Verificar contraseña
                Button(onClick = {
                    val pwd: UInt = 123456u
                    helper.verifyPassword(pwd) { ok ->
                        status = if (ok) "✅ Contraseña correcta" else "❌ Contraseña incorrecta"
                    }
                }) {
                    Text("🧾 Verify Password")
                }

            }
            Spacer(Modifier.height(8.dp))
            // ==========================================================
            // 📝 5. Log de actividad
            // ==========================================================
            Text("📝 Log de actividad", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                items(logList.reversed()) { entry ->
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    // ✅ Inicializar helper al montar pantalla
    DisposableEffect(Unit) {
        helper.start(
            onStatus = { msg ->
                if (msg.startsWith("CODE:")) {
                    val code = msg.removePrefix("CODE:").toInt()
                    when (code) {
                        0x00 -> status = "✅ Huella detectada"
                        0x02 -> status = "⚠️ No se detectó huella"
                        else -> status = "⚠️ Código desconocido: 0x${code.toString(16).uppercase()}"
                    }
                } else {
                    status = msg
                }
                addLog(msg)
            },
            onImage = { bmp -> fingerprint = bmp }
        )
        onDispose { helper.stop() }
    }
}

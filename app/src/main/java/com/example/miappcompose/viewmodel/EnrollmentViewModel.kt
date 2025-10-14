package com.example.miappcompose.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.miappcompose.data.repository.SyncRepository
import com.example.miappcompose.data.repository.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EnrollmentViewModel(
    private val syncRepository: SyncRepository
) : ViewModel() {

    private val _status = MutableStateFlow<String>("")
    val status = _status.asStateFlow()

    fun uploadTemplate(userId: Int) {
        viewModelScope.launch {
            when (val result = syncRepository.syncTemplateWithServer(userId)) {
                is SyncResult.Success -> _status.value = "✅ Sincronización exitosa"
                is SyncResult.Error -> _status.value = "❌ ${result.message}"
            }
        }
    }

    fun downloadTemplate(userId: Int) {
        viewModelScope.launch {
            when (val result = syncRepository.downloadTemplateFromServer(userId)) {
                is SyncResult.Success -> _status.value = "📥 Template descargado correctamente"
                is SyncResult.Error -> _status.value = "❌ ${result.message}"
            }
        }
    }

    fun saveTemplateLocal(userId: Int, base64: String) {
        viewModelScope.launch {
            when (val result = syncRepository.saveTemplateLocally(userId, base64)) {
                is SyncResult.Success -> _status.value = "💾 Guardado localmente"
                is SyncResult.Error -> _status.value = "❌ ${result.message}"
            }
        }
    }
}

package com.example.barsafe.ui.enrollment

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.barsafe.data.local.AppDatabase
import com.example.barsafe.data.model.FaceEmbedding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ManageFacesViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).faceEmbeddingDao()

    val faces = dao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun deleteFace(face: FaceEmbedding) {
        viewModelScope.launch {
            dao.delete(face)
            _message.value = "Wajah \"${face.name}\" berhasil dihapus"
        }
    }

    fun clearMessage() { _message.value = null }
}

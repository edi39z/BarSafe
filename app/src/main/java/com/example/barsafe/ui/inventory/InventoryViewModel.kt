package com.example.barsafe.ui.inventory

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.barsafe.data.local.AppDatabase
import com.example.barsafe.data.model.InventoryItem
import com.example.barsafe.data.remote.CloudinaryRepository
import com.example.barsafe.util.BitmapUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InventoryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).inventoryDao()
    private val cloudinary = CloudinaryRepository()
    private val ctx = application.applicationContext

    val items = dao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun addItem(
        name: String,
        description: String,
        category: String,
        estimatedValue: Long,
        photoUri: Uri?
    ) {
        viewModelScope.launch {
            _isUploading.value = true
            var photoUrl = ""
            if (photoUri != null) {
                val bitmap = BitmapUtils.uriToBitmap(ctx, photoUri)
                if (bitmap != null) {
                    val bytes = BitmapUtils.bitmapToJpegBytes(BitmapUtils.scaleBitmap(bitmap))
                    photoUrl = cloudinary.uploadImage(bytes, "$name.jpg") ?: ""
                }
            }
            dao.insert(InventoryItem(
                name = name,
                description = description,
                category = category,
                estimatedValue = estimatedValue,
                photoUrl = photoUrl
            ))
            _isUploading.value = false
            _message.value = "Barang berhasil ditambahkan"
        }
    }

    fun updateItem(item: InventoryItem, newPhotoUri: Uri?) {
        viewModelScope.launch {
            _isUploading.value = true
            var photoUrl = item.photoUrl
            if (newPhotoUri != null) {
                val bitmap = BitmapUtils.uriToBitmap(ctx, newPhotoUri)
                if (bitmap != null) {
                    val bytes = BitmapUtils.bitmapToJpegBytes(BitmapUtils.scaleBitmap(bitmap))
                    photoUrl = cloudinary.uploadImage(bytes, "${item.name}.jpg") ?: item.photoUrl
                }
            }
            dao.update(item.copy(photoUrl = photoUrl, updatedAt = System.currentTimeMillis()))
            _isUploading.value = false
            _message.value = "Barang berhasil diperbarui"
        }
    }

    fun deleteItem(item: InventoryItem) {
        viewModelScope.launch {
            dao.delete(item)
            _message.value = "Barang dihapus"
        }
    }

    fun clearMessage() { _message.value = null }
}

package com.example.barsafe.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

class CloudinaryRepository {

    companion object {
        const val CLOUD_NAME = "dmdmyrpve"
        const val UPLOAD_PRESET = "Skripsi"
        const val FOLDER = "Skripsi"
        const val UPLOAD_URL = "https://api.cloudinary.com/v1_1/$CLOUD_NAME/image/upload"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Upload image bytes to Cloudinary (unsigned preset).
     * Returns the secure_url string or null on failure.
     */
    suspend fun uploadImage(
        imageBytes: ByteArray,
        filename: String = "image.jpg"
    ): String? = withContext(Dispatchers.IO) {
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    filename,
                    imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .addFormDataPart("upload_preset", UPLOAD_PRESET)
                .addFormDataPart("folder", FOLDER)
                .build()

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: return@withContext null)
                json.optString("secure_url", null)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Upload from InputStream (e.g., ContentResolver).
     */
    suspend fun uploadStream(
        stream: InputStream,
        filename: String = "image.jpg"
    ): String? {
        return uploadImage(stream.readBytes(), filename)
    }
}

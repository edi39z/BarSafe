package com.example.barsafe.data.local

import androidx.room.TypeConverter
import java.nio.ByteBuffer

class Converters {

    @TypeConverter
    fun floatArrayToByteArray(value: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * Float.SIZE_BYTES)
        buffer.asFloatBuffer().put(value)
        return buffer.array()
    }

    @TypeConverter
    fun byteArrayToFloatArray(value: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(value)
        val result = FloatArray(value.size / Float.SIZE_BYTES)
        buffer.asFloatBuffer().get(result)
        return result
    }
}

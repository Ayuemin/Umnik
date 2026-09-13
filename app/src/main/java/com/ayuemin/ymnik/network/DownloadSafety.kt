package com.ayuemin.ymnik.network

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object DownloadSafety {
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024

    fun readImage(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= MAX_IMAGE_BYTES) { "Изображение превышает 16 МБ" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun checkEncodedLength(value: String) {
        require(value.length <= MAX_IMAGE_BYTES * 4 / 3 + 16) { "Изображение превышает 16 МБ" }
    }
}

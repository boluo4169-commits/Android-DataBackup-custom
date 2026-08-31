package com.xayah.core.network.io

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

/**
 * 带上传进度的 RequestBody：边读文件边写 sink，每写一块回调一次进度。
 * 用于 WebDAV 上传——libsardine 的 put(File) 不透出进度，导致 UI 一直停在 0 转圈。
 */
class CountingFileRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (written: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var written = 0L
        val buffer = ByteArray(256 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
                written += n
                onProgress(written, total)
            }
        }
    }
}

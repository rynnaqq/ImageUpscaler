package com.rimuru.twobytwo.domain.usecase

import com.rimuru.twobytwo.domain.model.ExportPolicy

interface StreamingImageIo {
    suspend fun encodeStreaming(
        width: Int,
        height: Int,
        destinationUri: String,
        policy: ExportPolicy,
        exifSourceUri: String?,
        produceRows: suspend (ByteArray) -> Unit,
    ): String
}

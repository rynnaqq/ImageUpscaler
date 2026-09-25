package com.rimuru.twobytwo.domain.usecase

import com.rimuru.twobytwo.domain.model.ExportPolicy

interface StreamingImageIo {
    fun encodeStreaming(
        width: Int,
        height: Int,
        destinationUri: String,
        policy: ExportPolicy,
        exifSourceUri: String?,
        produceRows: ((ByteArray) -> Unit) -> Unit,
    ): String
}

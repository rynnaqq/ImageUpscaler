package com.rimuru.twobytwo.data.history

data class HistoryRecord(
    val id: String,
    val sourceUri: String,
    val settingsJson: String,
    val outputUri: String?,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val parentId: String?,
)

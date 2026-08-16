package com.darkroot.ytdownloader

import java.util.UUID

enum class QueueStatus {
    QUEUED,
    FETCHING_INFO,
    DOWNLOADING,
    PAUSED,
    DONE,
    ERROR,
    CANCELLED
}

data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val qualityChoice: String,
    var title: String = "",
    var thumbnailUrl: String? = null,
    var durationSeconds: Long? = null,
    var estimatedBytes: Long? = null,
    var status: QueueStatus = QueueStatus.QUEUED,
    var progress: Int = 0,
    var errorMessage: String? = null,
    var savedFileName: String? = null,
    // yt-dlp's own subprocess id, needed to cancel/pause a running download
    var processId: String? = null
)

/** Formats a byte count into a short human-readable string, e.g. "42.3 MB". */
fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "size unknown"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return String.format("%.1f %s", value, units[unitIndex])
}

/** Formats a duration in seconds into "H:MM:SS" or "M:SS". */
fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds <= 0L) return "--:--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

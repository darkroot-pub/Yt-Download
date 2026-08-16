package com.darkroot.ytdownloader

import android.net.Uri

enum class MediaKind {
    VIDEO,
    AUDIO,
    PHOTO
}

data class LibraryItem(
    val name: String,
    val uri: Uri,
    val kind: MediaKind,
    val mimeType: String
)

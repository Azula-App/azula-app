package dev.azula.persist

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

// Android needs a Context, so the default is null; AzulaApplication builds the
// AppGraph with an AndroidMediaExporter(applicationContext) instead — same shape
// as platformBlobStore()/AndroidBlobStore.
actual fun platformMediaExporter(): MediaExporter? = null

/**
 * Saves received media into user-visible storage via [MediaStore]: images →
 * `Pictures/Azula`, video → `Movies/Azula` (both show up in the gallery), and
 * audio/other → `Download/Azula`. On API ≥29 this is a scoped-storage MediaStore
 * insert (no storage permission needed); on API 26–28 it falls back to writing
 * into the public external directories and triggering a media scan, which needs
 * `WRITE_EXTERNAL_STORAGE` (declared `maxSdkVersion=28` in the manifest).
 */
class AndroidMediaExporter(private val context: Context) : MediaExporter {

    // Blocking file/MediaStore IO; the caller (MediaService.exportReceived) already
    // launches this on a background scope (Dispatchers.Default), off the main thread.
    override suspend fun export(path: String, name: String, mime: String): String? {
        val src = File(path)
        if (!src.exists()) return null
        val displayName = name.ifBlank { "file" }
        val isImage = mime.startsWith("image/")
        val isVideo = mime.startsWith("video/")
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(src, displayName, mime, isImage, isVideo)
            } else {
                exportLegacy(src, displayName, isImage, isVideo)
            }
        }.getOrNull()
    }

    /** API ≥29: insert into MediaStore with IS_PENDING while copying, then clear it. */
    private fun exportViaMediaStore(src: File, name: String, mime: String, isImage: Boolean, isVideo: Boolean): String? {
        val resolver = context.contentResolver
        val collection = when {
            isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val relDir = when {
            isImage -> Environment.DIRECTORY_PICTURES + "/Azula"
            isVideo -> Environment.DIRECTORY_MOVIES + "/Azula"
            else -> Environment.DIRECTORY_DOWNLOADS + "/Azula"
        }
        val display = when {
            isImage -> "Photos"
            isVideo -> "Movies"
            else -> "Downloads/$name"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            if (mime.isNotBlank()) put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            val wrote = resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
                true
            } ?: false
            if (!wrote) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            display
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** API 26–28: write into the public external directory + scan so it's indexed. */
    private fun exportLegacy(src: File, name: String, isImage: Boolean, isVideo: Boolean): String? {
        val dirType = when {
            isImage -> Environment.DIRECTORY_PICTURES
            isVideo -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        @Suppress("DEPRECATION")
        val baseDir = File(Environment.getExternalStoragePublicDirectory(dirType), "Azula").also { it.mkdirs() }
        val dest = uniqueFile(baseDir, name)
        src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        return when {
            isImage -> "Photos"
            isVideo -> "Movies"
            else -> "Downloads/${dest.name}"
        }
    }
}

/** Not-yet-existing [File] in [dir] for [name], inserting ` (n)` before the
 *  extension on collision (`a.png` -> `a (1).png`). */
private fun uniqueFile(dir: File, name: String): File {
    val candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (true) {
        val f = File(dir, "$base ($n)$ext")
        if (!f.exists()) return f
        n++
    }
}

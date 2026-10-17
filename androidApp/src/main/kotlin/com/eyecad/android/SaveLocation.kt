package com.eyecad.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Where scans are saved. The default is Documents/Eyecad; the user can pick any folder instead
 * (through the system folder picker), and that folder becomes the new default.
 */
class SaveLocation(private val context: Context) {
    private val prefs = context.getSharedPreferences("eyecad", Context.MODE_PRIVATE)

    /** The chosen folder, if one was picked and its permission is still held. */
    private val tree: Uri?
        get() {
            val uri = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return null
            val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            return if (held) uri else null
        }

    /** Human-readable name of the default folder. */
    val label: String
        get() = tree?.let { Uri.decode(it.lastPathSegment ?: "").substringAfter(':').ifEmpty { "Chosen folder" } }
            ?: DEFAULT_FOLDER

    fun rememberFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun resetToDefault() {
        prefs.edit().remove(KEY_TREE).apply()
    }

    /** Copies the files into the default folder. Returns where they went. */
    fun save(files: List<File>): String {
        val t = tree
        when {
            t != null -> files.forEach { writeToTree(t, it) }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> files.forEach(::writeToDocuments)
            else -> {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Eyecad").apply { mkdirs() }
                files.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
                return dir.absolutePath
            }
        }
        return label
    }

    private fun writeToTree(tree: Uri, file: File) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // octet-stream keeps providers from appending their own extension.
        val doc = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", file.name)
            ?: error("Couldn't create ${file.name} in $label")
        context.contentResolver.openOutputStream(doc)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun writeToDocuments(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Eyecad")
        }
        val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: error("Couldn't save ${file.name} to $DEFAULT_FOLDER")
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private companion object {
        const val KEY_TREE = "save_tree"
        const val DEFAULT_FOLDER = "Documents/Eyecad"
    }
}

package com.familytv.app.data.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.io.InputStream

/**
 * Library on a Storage Access Framework tree (folder chosen by the parent in the system picker).
 * Uses one ContentResolver query per directory instead of DocumentFile (which queries per file).
 */
class SafFileSystem(context: Context, private val treeUri: Uri) : LibraryFileSystem {
    override val source = LibrarySourceRef(LibrarySourceRef.Type.SAF, treeUri.toString())
    private val resolver = context.applicationContext.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override fun root() = FsEntry("", true, 0, 0, docUri(rootDocId).toString(), rootDocId)

    override fun availability(): SourceAvailability {
        val persisted = try {
            resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        } catch (_: Exception) {
            false
        }
        return try {
            resolver.query(docUri(rootDocId), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null).use { c ->
                if (c != null && c.moveToFirst()) SourceAvailability.AVAILABLE else SourceAvailability.NOT_CONNECTED
            }
        } catch (_: SecurityException) {
            if (persisted) SourceAvailability.NOT_CONNECTED else SourceAvailability.PERMISSION_LOST
        } catch (_: Exception) {
            // Volume unmounted: the provider cannot resolve the document. A missing grant means the
            // permission is gone (e.g. it could not be persisted on this TV).
            if (persisted) SourceAvailability.NOT_CONNECTED else SourceAvailability.PERMISSION_LOST
        }
    }

    override fun list(dir: FsEntry): List<FsEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.ref)
        val cursor = try {
            resolver.query(childrenUri, PROJECTION, null, null, null)
        } catch (e: Exception) {
            throw IOException("Không đọc được thư mục: ${e.message}", e)
        } ?: throw IOException("Không đọc được thư mục")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    add(FsEntry(name, mime == Document.MIME_TYPE_DIR, size, modified, docUri(id).toString(), id))
                }
            }
        }
    }

    override fun open(entry: FsEntry): InputStream =
        try {
            resolver.openInputStream(Uri.parse(entry.uri))
        } catch (e: Exception) {
            throw IOException(e.message, e)
        } ?: throw IOException("Không mở được ${entry.name}")

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}

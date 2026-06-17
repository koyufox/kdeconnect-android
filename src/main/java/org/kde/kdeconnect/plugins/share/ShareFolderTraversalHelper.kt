/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a single file entry discovered during SAF tree traversal.
 */
data class FolderFileEntry(
    val documentFile: DocumentFile,
    val relativePath: String,      // e.g. "subdir/images/photo.jpg"
    val sanitizedFilename: String, // leaf filename, FAT-safe
    val mimeType: String,
    val size: Long,
    val lastModified: Long?
)

/**
 * A manifest describing all files found in a folder during traversal.
 */
data class FolderTransferManifest(
    val treeUri: String,
    val rootDisplayName: String,
    val files: List<FolderFileEntry>,
    val directories: List<String>,  // relative paths of all directories (including empty ones)
    val totalSize: Long,
    val truncated: Boolean = false  // true if traversal hit MAX_FILES
) {
    val isEmpty: Boolean get() = files.isEmpty()
    val totalFiles: Int get() = files.size
}

/**
 * Helper for traversing SAF document trees recursively.
 *
 * Handles path sanitization for cross-platform compatibility, cycle detection,
 * and safety limits on depth and file count.
 */
object ShareFolderTraversalHelper {
    private const val TAG = "ShareFolder"
    private const val MAX_DEPTH = 50
    private const val MAX_FILES = 5000

    /**
     * Traverse the SAF document tree rooted at [treeUri] and produce a manifest
     * of all files within it.
     *
     * @param context Android context for ContentResolver access
     * @param treeUri The tree URI obtained from ACTION_OPEN_DOCUMENT_TREE
     * @return A [FolderTransferManifest] describing all discovered files
     * @throws IllegalArgumentException if the tree URI cannot be resolved
     */
    fun traverse(context: Context, treeUri: Uri): FolderTransferManifest {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Cannot resolve tree URI: $treeUri")
        val rootDisplayName = rootDoc.name ?: treeUri.lastPathSegment ?: "folder"
        val entries = mutableListOf<FolderFileEntry>()
        val directories = mutableListOf<String>()
        val visited = HashSet<String>()  // normalized URI identity for cycle detection
        val totalSize = AtomicLong(0)

        val truncated = traverseRecursive(context, rootDoc, "", entries, directories, visited, 0, totalSize)

        return FolderTransferManifest(
            treeUri = treeUri.toString(),
            rootDisplayName = rootDisplayName,
            files = entries.toList(),
            directories = directories.toList(),
            totalSize = totalSize.get(),
            truncated = truncated
        )
    }

    private fun traverseRecursive(
        context: Context,
        dir: DocumentFile,
        relativePathPrefix: String,
        entries: MutableList<FolderFileEntry>,
        directories: MutableList<String>,
        visited: HashSet<String>,
        depth: Int,
        totalSize: AtomicLong
    ): Boolean {
        if (depth > MAX_DEPTH) {
            Log.w(TAG, "Max depth $MAX_DEPTH exceeded at $relativePathPrefix")
            return false
        }
        if (entries.size >= MAX_FILES) {
            Log.w(TAG, "Max file count $MAX_FILES reached, truncating")
            return true
        }

        // Normalize URI to detect symlinks/circular refs
        val docId = DocumentsContract.getDocumentId(dir.uri)
        if (!visited.add(docId)) return false

        // Record this directory's relative path (except the root "")
        if (relativePathPrefix.isNotEmpty()) {
            // Remove trailing slash for a clean directory path
            directories.add(relativePathPrefix.trimEnd('/'))
        }

        val children = dir.listFiles()
        for (child in children) {
            if (entries.size >= MAX_FILES) return true
            if (child.isDirectory) {
                val segment = sanitizePathSegment(child.name ?: "unnamed")
                val subTruncated = traverseRecursive(
                    context, child,
                    "$relativePathPrefix$segment/",
                    entries, directories, visited, depth + 1, totalSize
                )
                if (subTruncated) return true
            } else if (child.isFile) {
                val leafName = child.name ?: continue
                val sanitizedLeaf = sanitizePathSegment(leafName)
                val relPath = "$relativePathPrefix$sanitizedLeaf"
                val size = child.length()
                totalSize.addAndGet(size)
                entries.add(
                    FolderFileEntry(
                        documentFile = child,
                        relativePath = relPath,
                        sanitizedFilename = sanitizedLeaf,
                        mimeType = child.type ?: "*/*",
                        size = size,
                        lastModified = child.lastModified()
                    )
                )
            }
        }
        return false
    }

    /**
     * Sanitize a single path segment for cross-platform compatibility.
     * Replaces characters invalid on FAT/NTFS/ext4 with underscore.
     */
    @VisibleForTesting
    internal fun sanitizePathSegment(name: String): String {
        val sb = StringBuilder()
        for (c in name) {
            sb.append(if (isValidPathChar(c)) c else '_')
        }
        val result = sb.toString().trimEnd('.')
        return result.ifEmpty { "unnamed" }
    }

    private fun isValidPathChar(c: Char): Boolean {
        if (c.code in 0x00..0x1f || c.code == 0x7F) return false
        return c !in setOf('"', '*', '/', ':', '<', '>', '?', '\\', '|')
    }
}

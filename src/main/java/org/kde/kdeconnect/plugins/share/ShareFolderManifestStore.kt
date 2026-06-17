/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists folder transfer resume state as JSON files in the app's
 * internal storage. Uses files (not SharedPreferences) to avoid ANR
 * and size limits for potentially large manifests.
 */
object ShareFolderManifestStore {
    private const val TAG = "FolderManifestStore"
    private const val MANIFEST_DIR = "folder_transfer_manifests"

    /**
     * Lightweight entry for a single pending file, used during
     * resume state serialization.
     */
    data class PendingFileEntry(
        val uri: String,
        val relativePath: String,
        val filename: String,
        val size: Long
    )

    /**
     * Save the current transfer state so it can be resumed later.
     *
     * @param context Android context
     * @param deviceId The remote device ID
     * @param manifest The original folder transfer manifest
     * @param resumeIndex Number of files already sent
     * @param remaining List of files still to send
     */
    fun save(
        context: Context,
        deviceId: String,
        manifest: FolderTransferManifest,
        resumeIndex: Int,
        remaining: List<PendingFileEntry>
    ) {
        try {
            val dir = File(context.filesDir, MANIFEST_DIR).also { it.mkdirs() }
            val file = File(dir, "${deviceId}_${manifest.treeUri.hashCode()}.json")

            val json = JSONObject().apply {
                put("treeUri", manifest.treeUri)
                put("rootDisplayName", manifest.rootDisplayName)
                put("totalFiles", manifest.totalFiles)
                put("totalSize", manifest.totalSize)
                put("resumeIndex", resumeIndex)
                put("remaining", JSONArray().apply {
                    for (entry in remaining) {
                        put(JSONObject().apply {
                            put("uri", entry.uri)
                            put("relativePath", entry.relativePath)
                            put("filename", entry.filename)
                            put("size", entry.size)
                        })
                    }
                })
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save resume state", e)
        }
    }

    /**
     * Get all pending manifest files for a given device.
     */
    fun getPendingManifests(context: Context, deviceId: String): List<File> {
        val dir = File(context.filesDir, MANIFEST_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("${deviceId}_") }
            ?.toList() ?: emptyList()
    }

    /**
     * Load a saved manifest and resume index from a manifest file.
     *
     * @return A pair of (manifest, resumeIndex), or null if parsing fails
     */
    fun load(context: Context, file: File): Pair<FolderTransferManifest, Int>? {
        return try {
            val json = JSONObject(file.readText())
            val treeUri = json.getString("treeUri")
            val rootDisplayName = json.getString("rootDisplayName")
            val totalFiles = json.getInt("totalFiles")
            val totalSize = json.getLong("totalSize")
            val resumeIndex = json.getInt("resumeIndex")

            // The manifest loaded from disk won't have DocumentFile references
            // (those need to be reconstructed from URIs at resume time).
            // We create a stub manifest with just the metadata.
            val manifest = FolderTransferManifest(
                treeUri = treeUri,
                rootDisplayName = rootDisplayName,
                files = emptyList(),  // remaining files are in the JSON
                directories = emptyList(),
                totalSize = totalSize
            )

            Pair(manifest, resumeIndex)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load resume state from ${file.name}", e)
            null
        }
    }

    /**
     * Load the list of remaining [PendingFileEntry] from a saved manifest file.
     */
    fun loadRemainingEntries(file: File): List<PendingFileEntry> {
        return try {
            val json = JSONObject(file.readText())
            val remainingArr = json.getJSONArray("remaining")
            val entries = mutableListOf<PendingFileEntry>()
            for (i in 0 until remainingArr.length()) {
                val entry = remainingArr.getJSONObject(i)
                entries.add(
                    PendingFileEntry(
                        uri = entry.getString("uri"),
                        relativePath = entry.getString("relativePath"),
                        filename = entry.getString("filename"),
                        size = entry.getLong("size")
                    )
                )
            }
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load remaining entries from ${file.name}", e)
            emptyList()
        }
    }

    /**
     * Delete a saved manifest after successful completion or user dismissal.
     */
    fun delete(context: Context, treeUri: String, deviceId: String) {
        try {
            val dir = File(context.filesDir, MANIFEST_DIR)
            val file = File(dir, "${deviceId}_${treeUri.hashCode()}.json")
            file.delete()
        } catch (_: Exception) {
        }
    }
}

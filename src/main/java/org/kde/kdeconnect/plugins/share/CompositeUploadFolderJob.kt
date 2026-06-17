/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.net.Uri
import android.util.Log
import org.json.JSONArray

import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.async.BackgroundJob
import org.kde.kdeconnect.helpers.FilesHelper
import org.kde.kdeconnect_tp.R

/**
 * A [CompositeUploadFileJob] subclass that sends an entire folder tree
 * to a remote device, preserving directory structure via per-packet
 * "relativePath" and "folderTransfer" metadata fields.
 *
 * Implements skip-on-failure: a single file error does not abort the
 * entire transfer. Progress is tracked at the folder level.
 */
class CompositeUploadFolderJob(
    device: Device,
    callback: BackgroundJob.Callback<Void>,
    private val manifest: FolderTransferManifest,
    private val treeUri: Uri
) : CompositeUploadFileJob(device, callback) {

    private var completedFiles = 0
    private var failedFiles = 0
    private var skippedFiles = 0  // files that couldn't be opened

    // Built once in init, sent once in run() before the file loop.
    // Contains all directory paths (including empty ones) for the receiver to pre-create.
    private val directoryPaths: JSONArray = JSONArray().apply {
        put(manifest.rootDisplayName)
        for (dir in manifest.directories) {
            put(manifest.rootDisplayName + "/" + dir)
        }
    }

    init {
        // Pre-populate networkPacketList from the manifest.
        // Each file entry becomes a NetworkPacket with folder metadata attached.
        for (entry in manifest.files) {
            val np = FilesHelper.uriToNetworkPacket(
                device.context, entry.documentFile.uri, SharePlugin.PACKET_TYPE_SHARE_REQUEST
            )
            if (np != null) {
                np["relativePath"] = manifest.rootDisplayName + "/" + entry.relativePath
                np["folderTransfer"] = true
                // Store for resume support
                np["documentUri"] = entry.documentFile.uri.toString()
                addNetworkPacket(np)
            } else {
                skippedFiles++
                Log.w("FolderJob", "Skipping unopenable: ${entry.relativePath}")
            }
        }
    }

    /**
     * Resume constructor: reconstructs the job from saved state.
     * [manifest] should carry only metadata (totalFiles, totalSize, treeUri, rootDisplayName) —
     * its [FolderTransferManifest.files] list is ignored. Packets are rebuilt from [remaining].
     */
    constructor(
        device: Device,
        callback: BackgroundJob.Callback<Void>,
        manifest: FolderTransferManifest,
        treeUri: Uri,
        resumeIndex: Int,
        remaining: List<ShareFolderManifestStore.PendingFileEntry>
    ) : this(device, callback, manifest, treeUri) {
        completedFiles = resumeIndex
        currentFileNum = resumeIndex

        for (entry in remaining) {
            val uri = Uri.parse(entry.uri)
            val np = FilesHelper.uriToNetworkPacket(
                device.context, uri, SharePlugin.PACKET_TYPE_SHARE_REQUEST
            )
            if (np != null) {
                np["relativePath"] = entry.relativePath
                np["folderTransfer"] = true
                np["documentUri"] = entry.uri
                addNetworkPacket(np)
            } else {
                skippedFiles++
                Log.w("FolderJob", "Resume skipping unopenable: ${entry.relativePath}")
            }
        }
    }

    override fun run() {
        isRunning = true

        // Send directory paths once before starting the file loop so the
        // receiver can pre-create the full directory tree (including empty dirs).
        val dirUpdate = NetworkPacket(SharePlugin.PACKET_TYPE_SHARE_REQUEST_UPDATE).apply {
            this["directoryPaths"] = directoryPaths
            this["folderTransfer"] = true
            this[SharePlugin.KEY_NUMBER_OF_FILES] = manifest.totalFiles
            this[SharePlugin.KEY_TOTAL_PAYLOAD_SIZE] = manifest.totalSize
        }
        device.sendPacket(dirUpdate)

        var done: Boolean
        synchronized(lock) { done = networkPacketList.isEmpty() }

        // Track whether we have already posted a final notification so the
        // catch / finally blocks don't overwrite a success notification with
        // a failure one.
        var completed = false

        try {
            while (!done && !isCancelled) {
                synchronized(lock) {
                    currentNetworkPacket = networkPacketList.removeAt(0)
                }
                currentFileName = currentNetworkPacket.getString("filename")
                currentFileNum++

                setFolderProgress()

                val success = sendCurrentPacket()

                if (!success) {
                    failedFiles++
                }
                completedFiles = currentFileNum

                // Periodic resume save (every 5 completed files)
                if (completedFiles % 5 == 0) {
                    saveResumeState()
                }

                synchronized(lock) { done = networkPacketList.isEmpty() }
            }

            if (isCancelled) {
                uploadNotification.cancel()
                saveResumeState()
            } else {
                setProgress(100)
                showCompletionNotification()
                completed = true
                ShareFolderManifestStore.delete(device.context, treeUri.toString(), device.deviceId)
                reportResult(null)
            }
        } catch (e: Exception) {
            Log.e("FolderJob", "Folder transfer failed", e)
            if (!completed) {
                showFailureNotification(e)
                reportError(e)
            }
        } finally {
            isRunning = false
            cleanupRemainingPayloads()
        }
    }

    /**
     * Override to wrap the parent's send in try/catch for skip-on-failure.
     * @return true if the packet was sent successfully
     */
    override fun sendCurrentPacket(): Boolean {
        return try {
            super.sendCurrentPacket()
        } catch (e: RuntimeException) {
            Log.e("FolderJob", "Failed to send: $currentFileName", e)
            false
        }
    }

    /**
     * Override parent's [CompositeUploadFileJob.setProgress] so that the
     * byte-level progress callbacks from [Device.SendPacketStatusCallback]
     * use folder-specific notification text instead of the parent's
     * file-oriented plurals.  Without this, the folder notification
     * flip-flops between the two string resources, and for the last file
     * the progress may appear stuck below 100 % even though the transfer
     * has already finished.
     */
    override fun setProgress(progress: Int) {
        uploadNotification.setProgress(
            progress,
            device.context.getString(
                R.string.sending_folder_progress,
                manifest.rootDisplayName,
                currentFileName,
                currentFileNum,
                manifest.totalFiles
            )
        )
        uploadNotification.show()
    }

    private fun setFolderProgress() {
        val progress = if (manifest.totalFiles > 0) {
            (completedFiles * 100) / manifest.totalFiles
        } else {
            0
        }
        if (progress != prevProgressPercentage) {
            prevProgressPercentage = progress
            setProgress(progress)
        }
    }

    private fun saveResumeState() {
        val remaining = mutableListOf<ShareFolderManifestStore.PendingFileEntry>()
        synchronized(lock) {
            for (np in networkPacketList) {
                val uriStr = np.getStringOrNull("documentUri") ?: continue
                val relPath = np.getStringOrNull("relativePath") ?: continue
                val filename = np.getStringOrNull("filename") ?: continue
                val size = np.payloadSize
                remaining.add(
                    ShareFolderManifestStore.PendingFileEntry(
                        uri = uriStr,
                        relativePath = relPath,
                        filename = filename,
                        size = if (size >= 0) size else 0
                    )
                )
            }
        }
        ShareFolderManifestStore.save(
            context = device.context,
            deviceId = device.deviceId,
            manifest = manifest,
            resumeIndex = completedFiles,
            remaining = remaining
        )
    }

    private fun showCompletionNotification() {
        try {
            val message: String
            if (failedFiles == 0 && skippedFiles == 0) {
                message = device.context.resources.getQuantityString(
                    R.plurals.sent_folder_title, completedFiles,
                    manifest.rootDisplayName, completedFiles, device.name
                )
            } else {
                message = device.context.getString(
                    R.string.sent_folder_partial,
                    manifest.rootDisplayName,
                    completedFiles - failedFiles,
                    completedFiles + skippedFiles,
                    device.name
                )
            }
            uploadNotification.setFinished(message)
            uploadNotification.show()
        } catch (e: Exception) {
            Log.e("FolderJob", "Failed to show completion notification", e)
        }
    }

    private fun showFailureNotification(error: Exception) {
        try {
            val message = device.context.getString(
                R.string.sent_folder_partial,
                manifest.rootDisplayName,
                completedFiles - failedFiles,
                completedFiles + skippedFiles,
                device.name
            )
            uploadNotification.setFailed(message)
            uploadNotification.show()
        } catch (e: Exception) {
            Log.e("FolderJob", "Failed to show failure notification", e)
        }
    }

    private fun cleanupRemainingPayloads() {
        synchronized(lock) {
            for (np in networkPacketList) {
                try {
                    np.payload?.close()
                } catch (_: Exception) {
                }
            }
            networkPacketList.clear()
        }
    }
}

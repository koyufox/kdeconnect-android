/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.ui.compose.components.KdeCard
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect.ui.compose.KdeTopAppBar
import org.kde.kdeconnect_tp.R

/**
 * Intermediate chooser shown when the user taps "Send files" on an Android peer
 * with the "Send folder" preference enabled. Presents two card options:
 * "Send file" (existing behavior) and "Send folder" (ACTION_OPEN_DOCUMENT_TREE).
 */
class SendFolderChooserActivity : AppCompatActivity() {

    private var deviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra("deviceId")

        setContent {
            KdeTheme(this) {
                Scaffold(
                    modifier = Modifier.safeDrawingPadding(),
                    topBar = {
                        KdeTopAppBar(
                            title = getString(R.string.send_files),
                            navIconOnClick = { onBackPressedDispatcher.onBackPressed() },
                            navIconDescription = getString(androidx.appcompat.R.string.abc_action_bar_up_description),
                        )
                    },
                ) { scaffoldPadding ->
                    FolderChooserScreen(
                        modifier = Modifier.padding(scaffoldPadding),
                        onSendFile = { launchSendFile() },
                        onSendFolder = { launchSendFolder() }
                    )
                }
            }
        }
    }

    private fun launchSendFile() {
        val intent = Intent(this, SendFileActivity::class.java)
        intent.putExtra("deviceId", deviceId)
        startActivity(intent)
        finish()
    }

    private fun launchSendFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_FOLDER_PICKER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER_PICKER
            && resultCode == Activity.RESULT_OK
            && data?.data != null
        ) {
            val treeUri = data.data!!
            ThreadHelper.execute {
                val plugin = KdeConnect.getInstance()
                    .getDevicePlugin(deviceId, SharePlugin::class.java) ?: return@execute
                plugin.sendFolder(treeUri)
            }
            finish()
        }
    }

    companion object {
        private const val REQUEST_FOLDER_PICKER = 1001
    }
}

@Composable
private fun FolderChooserScreen(
    modifier: Modifier = Modifier,
    onSendFile: () -> Unit,
    onSendFolder: () -> Unit
) {
    Column(modifier = modifier.padding(16.dp)) {
        KdeCard(
            onClick = onSendFile,
            content = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.share_plugin_action_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        Text(
                            text = stringResource(id = R.string.send_file_option),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(id = R.string.send_file_option_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )

        KdeCard(
            onClick = onSendFolder,
            content = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_folder_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        Text(
                            text = stringResource(id = R.string.send_folder_option),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(id = R.string.send_folder_option_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }
}

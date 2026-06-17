/*
 * SPDX-FileCopyrightText: 2018 Erik Duisters <e.duisters1@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.kde.kdeconnect.KdeConnect;

public class ShareBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        switch (intent.getAction()) {
            case SharePlugin.ACTION_CANCEL_SHARE:
                cancelShare(context, intent);
                break;
            case SharePlugin.ACTION_RESUME_SHARE:
                resumeShare(context, intent);
                break;
            case SharePlugin.ACTION_DISMISS_SHARE:
                dismissShare(context, intent);
                break;
            default:
                Log.d("ShareBroadcastReceiver", "Unhandled Action received: " + intent.getAction());
        }
    }

    private void cancelShare(Context context, Intent intent) {
        if (!intent.hasExtra(SharePlugin.CANCEL_SHARE_BACKGROUND_JOB_ID_EXTRA) ||
            !intent.hasExtra(SharePlugin.CANCEL_SHARE_DEVICE_ID_EXTRA)) {
            Log.e("ShareBroadcastReceiver", "cancelShare() - not all expected extra's are present. Ignoring this cancel intent");
            return;
        }

        long jobId = intent.getLongExtra(SharePlugin.CANCEL_SHARE_BACKGROUND_JOB_ID_EXTRA, -1);
        String deviceId = intent.getStringExtra(SharePlugin.CANCEL_SHARE_DEVICE_ID_EXTRA);

        SharePlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, SharePlugin.class);
        if (plugin == null) {
            return;
        }
        plugin.cancelJob(jobId);
    }

    private void resumeShare(Context context, Intent intent) {
        if (!intent.hasExtra(SharePlugin.CANCEL_SHARE_DEVICE_ID_EXTRA) ||
            !intent.hasExtra(SharePlugin.RESUME_MANIFEST_FILE_PATH_EXTRA)) {
            Log.e("ShareBroadcastReceiver", "resumeShare() - not all expected extras are present");
            return;
        }

        String deviceId = intent.getStringExtra(SharePlugin.CANCEL_SHARE_DEVICE_ID_EXTRA);
        String manifestFilePath = intent.getStringExtra(SharePlugin.RESUME_MANIFEST_FILE_PATH_EXTRA);

        SharePlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, SharePlugin.class);
        if (plugin == null) {
            return;
        }
        plugin.resumeFolderTransfer(manifestFilePath);
    }

    private void dismissShare(Context context, Intent intent) {
        if (!intent.hasExtra(SharePlugin.CANCEL_SHARE_DEVICE_ID_EXTRA) ||
            !intent.hasExtra(SharePlugin.RESUME_MANIFEST_FILE_PATH_EXTRA)) {
            Log.e("ShareBroadcastReceiver", "dismissShare() - not all expected extras are present");
            return;
        }

        String deviceId = intent.getStringExtra(SharePlugin.CANCEL_SHARE_DEVICE_ID_EXTRA);
        String manifestFilePath = intent.getStringExtra(SharePlugin.RESUME_MANIFEST_FILE_PATH_EXTRA);

        SharePlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, SharePlugin.class);
        if (plugin == null) {
            return;
        }
        plugin.dismissFolderTransfer(manifestFilePath);
    }
}

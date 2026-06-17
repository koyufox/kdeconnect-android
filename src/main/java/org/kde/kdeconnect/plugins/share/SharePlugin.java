/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import androidx.core.content.LocusIdCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.os.BundleCompat;
import androidx.preference.PreferenceManager;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.kde.kdeconnect.helpers.FilesHelper;
import org.kde.kdeconnect.helpers.IntentHelper;
import org.kde.kdeconnect.helpers.NotificationHelper;
import org.kde.kdeconnect.helpers.ThreadHelper;
import org.kde.kdeconnect.NetworkPacket;
import org.kde.kdeconnect.DeviceType;
import org.kde.kdeconnect.plugins.Plugin;
import org.kde.kdeconnect.plugins.PluginFactory;
import org.kde.kdeconnect.ui.MainActivity;
import org.kde.kdeconnect.ui.PluginSettingsFragment;
import org.kde.kdeconnect.async.BackgroundJob;
import org.kde.kdeconnect.async.BackgroundJobHandler;
import org.kde.kdeconnect_tp.R;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import kotlin.Pair;
import kotlin.Unit;

/**
 * A Plugin for sharing and receiving files and uris.
 * <p>
 *     All of the associated I/O work is scheduled on background
 *     threads by {@link BackgroundJobHandler}.
 * </p>
 */
@PluginFactory.LoadablePlugin
public class SharePlugin extends Plugin {
    final static String ACTION_CANCEL_SHARE = "org.kde.kdeconnect.plugins.share.CancelShare";
    final static String ACTION_RESUME_SHARE = "org.kde.kdeconnect.plugins.share.ResumeShare";
    final static String ACTION_DISMISS_SHARE = "org.kde.kdeconnect.plugins.share.DismissShare";
    final static String CANCEL_SHARE_DEVICE_ID_EXTRA = "deviceId";
    final static String CANCEL_SHARE_BACKGROUND_JOB_ID_EXTRA = "backgroundJobId";
    final static String RESUME_MANIFEST_FILE_PATH_EXTRA = "manifestFilePath";

    final static String PACKET_TYPE_SHARE_REQUEST = "kdeconnect.share.request";
    final static String PACKET_TYPE_SHARE_REQUEST_UPDATE = "kdeconnect.share.request.update";

    final static String KEY_NUMBER_OF_FILES = "numberOfFiles";
    final static String KEY_TOTAL_PAYLOAD_SIZE = "totalPayloadSize";

    private final BackgroundJobHandler backgroundJobHandler;
    private final Handler handler;

    private CompositeReceiveFileJob receiveFileJob;
    private CompositeUploadFileJob uploadFileJob;
    private final Callback receiveFileJobCallback;

    // Holder for directory paths from an update packet that arrives
    // before the first file packet (and thus before receiveFileJob exists).
    @Nullable
    private org.json.JSONArray pendingDirectoryPaths;

    public static final String KEY_UNREACHABLE_URL_LIST = "key_unreachable_url_list";
    private SharedPreferences mSharedPrefs;

    public SharePlugin() {
        backgroundJobHandler = BackgroundJobHandler.newFixedThreadPoolBackgroundJobHandler(5);
        handler = new Handler(Looper.getMainLooper());
        receiveFileJobCallback = new Callback();
    }

    @Override
    public boolean onCreate() {
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        createOrUpdateDynamicShortcut(null);
        // Deliver URLs previously shared to this device now that it's connected
        deliverPreviouslySentIntents();
        checkForPendingFolderResume();
        return true;
    }

    private void checkForPendingFolderResume() {
        List<File> pending = ShareFolderManifestStore.INSTANCE.getPendingManifests(
                context, device.getDeviceId());
        for (File manifestFile : pending) {
            Pair<FolderTransferManifest, Integer> result =
                    ShareFolderManifestStore.INSTANCE.load(context, manifestFile);
            if (result == null) {
                manifestFile.delete();
                continue;
            }

            FolderTransferManifest manifest = result.getFirst();
            int resumeIndex = result.getSecond();
            int remainingCount = manifest.getTotalFiles() - resumeIndex;

            if (remainingCount <= 0) {
                // Transfer was already complete; just clean up
                manifestFile.delete();
                continue;
            }

            handler.post(() -> showResumeNotification(manifestFile, manifest, resumeIndex, remainingCount));
        }
    }

    private void showResumeNotification(File manifestFile, FolderTransferManifest manifest,
                                         int resumeIndex, int remainingCount) {
        Context ctx = context;
        int notificationId = manifestFile.hashCode();

        Intent resumeIntent = new Intent(ctx, ShareBroadcastReceiver.class);
        resumeIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        resumeIntent.setAction(ACTION_RESUME_SHARE);
        resumeIntent.putExtra(CANCEL_SHARE_DEVICE_ID_EXTRA, device.getDeviceId());
        resumeIntent.putExtra(RESUME_MANIFEST_FILE_PATH_EXTRA, manifestFile.getAbsolutePath());
        PendingIntent resumePendingIntent = PendingIntent.getBroadcast(
                ctx, 0, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent dismissIntent = new Intent(ctx, ShareBroadcastReceiver.class);
        dismissIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        dismissIntent.setAction(ACTION_DISMISS_SHARE);
        dismissIntent.putExtra(CANCEL_SHARE_DEVICE_ID_EXTRA, device.getDeviceId());
        dismissIntent.putExtra(RESUME_MANIFEST_FILE_PATH_EXTRA, manifestFile.getAbsolutePath());
        PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(
                ctx, 1, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = ctx.getString(
                R.string.resume_folder_transfer_title, device.getName());
        String message = ctx.getString(
                R.string.resume_folder_transfer_message,
                manifest.getRootDisplayName(), resumeIndex, manifest.getTotalFiles());

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                ctx, NotificationHelper.Channels.FILETRANSFER_UPLOAD)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(R.drawable.ic_reject_pairing_24dp,
                        ctx.getString(R.string.resume), resumePendingIntent)
                .addAction(R.drawable.ic_reject_pairing_24dp,
                        ctx.getString(R.string.dismiss_transfer), dismissPendingIntent);

        NotificationManager nm = ContextCompat.getSystemService(ctx, NotificationManager.class);
        nm.notify(notificationId, builder.build());
    }

    void resumeFolderTransfer(String manifestFilePath) {
        if (uploadFileJob != null) {
            Toast.makeText(context, R.string.transfer_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }

        File manifestFile = new File(manifestFilePath);
        if (!manifestFile.exists()) {
            return;
        }

        // Dismiss the resume notification
        NotificationManager nm = ContextCompat.getSystemService(context, NotificationManager.class);
        nm.cancel(manifestFile.hashCode());

        // Load saved state
        Pair<FolderTransferManifest, Integer> result =
                ShareFolderManifestStore.INSTANCE.load(context, manifestFile);
        if (result == null) {
            manifestFile.delete();
            return;
        }

        FolderTransferManifest manifest = result.getFirst();
        int resumeIndex = result.getSecond();

        List<ShareFolderManifestStore.PendingFileEntry> remaining =
                ShareFolderManifestStore.INSTANCE.loadRemainingEntries(manifestFile);

        if (remaining.isEmpty()) {
            manifestFile.delete();
            return;
        }

        // Validate tree URI is still accessible
        Uri treeUri = Uri.parse(manifest.getTreeUri());
        try {
            context.getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.w("SharePlugin", "Cannot resume: tree URI permission lost for " + manifest.getRootDisplayName());
            Toast.makeText(context, R.string.send_folder_empty, Toast.LENGTH_SHORT).show();
            manifestFile.delete();
            return;
        }

        CompositeUploadFolderJob job = new CompositeUploadFolderJob(
                getDevice(), receiveFileJobCallback, manifest, treeUri, resumeIndex, remaining);
        uploadFileJob = job;
        backgroundJobHandler.runJob(job);

        // Delete manifest file now — saveResumeState() will recreate it as the transfer progresses
        manifestFile.delete();
    }

    void dismissFolderTransfer(String manifestFilePath) {
        File manifestFile = new File(manifestFilePath);
        manifestFile.delete();

        // Dismiss the resume notification
        NotificationManager nm = ContextCompat.getSystemService(context, NotificationManager.class);
        nm.cancel(manifestFile.hashCode());
    }

    @Override
    public void onDestroy() {
        for (ShortcutInfoCompat shortcut : ShortcutManagerCompat.getDynamicShortcuts(context)) {
            if (!shortcut.getId().equals(device.getDeviceId())) continue;
            if (!device.isReachable() && shortcut.isPinned()) {
                // Create an updated shortcut with the same ID
                createOrUpdateDynamicShortcut(shortcut);
                break;
            } else {
                ShortcutManagerCompat.removeLongLivedShortcuts(context, List.of(shortcut.getId()));
            }
        }
        super.onDestroy();
    }

    private void createOrUpdateDynamicShortcut(@Nullable ShortcutInfoCompat shortcutToUpdate) {
        final boolean isNewShortcut = shortcutToUpdate == null;
        IconCompat icon = IconCompat.createWithResource(
                context, device.getDeviceType().toShortcutDrawableId());
        Intent shortcutIntent = null;
        if (isNewShortcut) {
            shortcutIntent = new Intent(context, MainActivity.class);
            shortcutIntent.setAction(Intent.ACTION_VIEW);
            shortcutIntent.putExtra(MainActivity.EXTRA_DEVICE_ID, device.getDeviceId());
        }
        ShortcutInfoCompat shortcut = new ShortcutInfoCompat
                .Builder(context, device.getDeviceId())
                .setIntent(isNewShortcut ? shortcutIntent : shortcutToUpdate.getIntent())
                .setIcon(icon)
                .setShortLabel(isNewShortcut ? device.getName()
                        : context.getString(
                                R.string.unreachable_device_dynamic_shortcut,
                                shortcutToUpdate.getShortLabel()))
                .setCategories(isNewShortcut ? Set.of("org.kde.kdeconnect.category.SHARE_TARGET")
                        : shortcutToUpdate.getCategories())
                .setLocusId(isNewShortcut ? new LocusIdCompat(device.getDeviceId())
                        : shortcutToUpdate.getLocusId())
                .build();
        if (isNewShortcut) {
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut);
        } else {
            ShortcutManagerCompat.updateShortcuts(context, List.of(shortcut));
        }
    }

    private void deliverPreviouslySentIntents() {
        Set<String> currentUrlSet = mSharedPrefs.getStringSet(KEY_UNREACHABLE_URL_LIST + device.getDeviceId(), null);
        if (currentUrlSet != null) {
            for (String url : currentUrlSet) {
                Intent intent;
                try {
                    intent = Intent.parseUri(url, 0);
                    intent.putExtra(Intent.EXTRA_TEXT, url);
                } catch (URISyntaxException ex) {
                    Log.e("SharePlugin", "Malformed URI");
                    continue;
                }
                if (intent != null) {
                    share(intent);
                }
            }
            mSharedPrefs.edit().putStringSet(KEY_UNREACHABLE_URL_LIST + device.getDeviceId(), null).apply();
        }
    }

    @Override
    protected int getOptionalPermissionExplanation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return R.string.share_notifications_explanation;
        } else {
            return R.string.share_optional_permission_explanation;
        }
    }

    @Override
    public @NonNull String getDisplayName() {
        return context.getResources().getString(R.string.pref_plugin_sharereceiver);
    }

    @Override
    public @NonNull String getDescription() {
        return context.getResources().getString(R.string.pref_plugin_sharereceiver_desc);
    }

    @Override
    public @NotNull List<@NotNull PluginUiButton> getUiButtons() {
        return List.of(new PluginUiButton(context.getString(R.string.send_files), R.drawable.share_plugin_action_24dp, parentActivity -> {
            boolean isAndroidPeer = device.getDeviceType() == DeviceType.PHONE
                    || device.getDeviceType() == DeviceType.TABLET
                    || device.getDeviceType() == DeviceType.TV;
            if (isAndroidPeer && ShareSettingsFragment.isSendFolderEnabled(context)) {
                if (uploadFileJob != null) {
                    Toast.makeText(context, R.string.transfer_in_progress, Toast.LENGTH_SHORT).show();
                    return Unit.INSTANCE;
                }
                Intent intent = new Intent(parentActivity, SendFolderChooserActivity.class);
                intent.putExtra("deviceId", getDevice().getDeviceId());
                parentActivity.startActivity(intent);
            } else {
                Intent intent = new Intent(parentActivity, SendFileActivity.class);
                intent.putExtra("deviceId", getDevice().getDeviceId());
                parentActivity.startActivity(intent);
            }
            return Unit.INSTANCE;
        }));
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    @WorkerThread
    public boolean onPacketReceived(@NonNull NetworkPacket np) {
        try {
            if (np.getType().equals(PACKET_TYPE_SHARE_REQUEST_UPDATE)) {
                if (receiveFileJob != null && receiveFileJob.isRunning()) {
                    receiveFileJob.updateTotals(np.getInt(KEY_NUMBER_OF_FILES), np.getLong(KEY_TOTAL_PAYLOAD_SIZE));
                    // Forward directory paths for folder transfers (preserves empty dirs)
                    org.json.JSONArray dirPaths = np.getJSONArray("directoryPaths");
                    if (dirPaths != null) {
                        receiveFileJob.setDirectoryPaths(dirPaths);
                    }
                } else {
                    // receiveFileJob doesn't exist yet — store directory paths for when it's created
                    org.json.JSONArray dirPaths = np.getJSONArray("directoryPaths");
                    if (dirPaths != null) {
                        pendingDirectoryPaths = dirPaths;
                    }
                }

                return true;
            }

            if (np.has("filename")) {
                receiveFile(np);
            } else if (np.has("text")) {
                Log.i("SharePlugin", "hasText");
                receiveText(np);
            } else if (np.has("url")) {
                receiveUrl(np);
            } else {
                Log.e("SharePlugin", "Error: Nothing attached!");
            }

        } catch (Exception e) {
            Log.e("SharePlugin", "Exception");
            e.printStackTrace();
        }

        return true;
    }

    private void receiveUrl(NetworkPacket np) {
        String url = np.getString("url");

        Log.i("SharePlugin", "hasUrl: " + url);

        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        IntentHelper.startActivityFromBackgroundOrCreateNotification(context, browserIntent, url);
    }

    private void receiveText(NetworkPacket np) {
        String text = np.getString("text");
        ClipboardManager cm = ContextCompat.getSystemService(context, ClipboardManager.class);
        cm.setText(text);
        handler.post(() -> Toast.makeText(context, R.string.shareplugin_text_saved, Toast.LENGTH_LONG).show());
    }

    @WorkerThread
    private void receiveFile(NetworkPacket np) {
        CompositeReceiveFileJob job;

        boolean hasNumberOfFiles = np.has(KEY_NUMBER_OF_FILES);
        boolean isOpen = np.getBoolean("open", false);

        if (hasNumberOfFiles && !isOpen && receiveFileJob != null) {
            job = receiveFileJob;
        } else {
            job = new CompositeReceiveFileJob(getDevice(), receiveFileJobCallback);
            // Attach any directory paths that arrived before the first file packet
            if (pendingDirectoryPaths != null) {
                job.setDirectoryPaths(pendingDirectoryPaths);
                pendingDirectoryPaths = null;
            }
        }

        if (!hasNumberOfFiles) {
            np.set(KEY_NUMBER_OF_FILES, 1);
            np.set(KEY_TOTAL_PAYLOAD_SIZE, np.getPayloadSize());
        }

        job.addNetworkPacket(np);

        if (job != receiveFileJob) {
            if (hasNumberOfFiles && !isOpen) {
                receiveFileJob = job;
            }
            backgroundJobHandler.runJob(job);
        }
    }

    @Override
    public PluginSettingsFragment getSettingsFragment(Activity activity) {
        return ShareSettingsFragment.newInstance(getPluginKey(), R.xml.shareplugin_preferences);
    }

    void sendFolder(final Uri treeUri) {
        // Persist URI permission for resume after reboot
        context.getContentResolver().takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

        handler.post(() -> Toast.makeText(context, R.string.sending_folder_preparing, Toast.LENGTH_SHORT).show());

        // Traverse on background thread
        ThreadHelper.execute(() -> {
            FolderTransferManifest manifest = ShareFolderTraversalHelper.INSTANCE.traverse(context, treeUri);

            handler.post(() -> {
                if (manifest.isEmpty()) {
                    Toast.makeText(context, R.string.send_folder_empty, Toast.LENGTH_SHORT).show();
                    return;
                }

                if (manifest.getTruncated()) {
                    Toast.makeText(context, R.string.folder_transfer_max_files, Toast.LENGTH_LONG).show();
                }

                CompositeUploadFolderJob job = new CompositeUploadFolderJob(
                        getDevice(), receiveFileJobCallback, manifest, treeUri);
                uploadFileJob = job;
                backgroundJobHandler.runJob(job);
            });
        });
    }

    void sendUriList(final ArrayList<Uri> uriList) {
        CompositeUploadFileJob job;

        if (uploadFileJob == null) {
            job = new CompositeUploadFileJob(getDevice(), this.receiveFileJobCallback);
        } else {
            job = uploadFileJob;
        }

        //Read all the data early, as we only have permissions to do it while the activity is alive
        for (Uri uri : uriList) {
            NetworkPacket np = FilesHelper.uriToNetworkPacket(context, uri, PACKET_TYPE_SHARE_REQUEST);

            if (np != null) {
                job.addNetworkPacket(np);
            }
        }

        if (job != uploadFileJob) {
            uploadFileJob = job;
            backgroundJobHandler.runJob(uploadFileJob);
        }
    }

    public void share(Intent intent) {
        Bundle extras = intent.getExtras();
        ArrayList<Uri> streams = streamsFromIntent(intent, extras);
        if (streams != null && !streams.isEmpty()) {
            sendUriList(streams);
            return;
        }
        if (extras != null) {
            String text = extras.getString(Intent.EXTRA_TEXT);
            if (StringUtils.isNotEmpty(text)) {
                Log.i("SharePlugin", "Intent contains text to share");

                //Hack: Detect shared youtube videos, so we can open them in the browser instead of as text
                String subject = extras.getString(Intent.EXTRA_SUBJECT);
                if (subject != null && subject.endsWith("YouTube")) {
                    int index = text.indexOf(": http://youtu.be/");
                    if (index > 0) {
                        text = text.substring(index + 2); //Skip ": "
                    }
                }

                boolean isUrl;
                try {
                    new URL(text);
                    isUrl = true;
                } catch (MalformedURLException e) {
                    isUrl = false;
                }
                NetworkPacket np = new NetworkPacket(SharePlugin.PACKET_TYPE_SHARE_REQUEST);
                np.set(isUrl ? "url" : "text", text);
                device.sendPacket(np);
                return;
            }
        }
        Log.e("SharePlugin", "There's nothing we know how to share");
    }

    private ArrayList<Uri> streamsFromIntent(Intent intent, Bundle extras) {
        if (extras == null || !extras.containsKey(Intent.EXTRA_STREAM)) {
            return null;
        }
        Log.i("SharePlugin", "Intent contains streams to share");
        ArrayList<Uri> uriList;
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            uriList = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri.class);
        } else {
            uriList = new ArrayList<>();
            uriList.add(BundleCompat.getParcelable(extras, Intent.EXTRA_STREAM, Uri.class));
        }
        uriList.removeAll(Collections.singleton(null));
        if (uriList.isEmpty()) {
            Log.w("SharePlugin", "All streams were null");
        }
        return uriList;
    }

    @Override
    public @NonNull String[] getSupportedPacketTypes() {
        return new String[]{PACKET_TYPE_SHARE_REQUEST, PACKET_TYPE_SHARE_REQUEST_UPDATE};
    }

    @Override
    public @NonNull String[] getOutgoingPacketTypes() {
        return new String[]{PACKET_TYPE_SHARE_REQUEST};
    }

    @Override
    public @NonNull String[] getOptionalPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return ArrayUtils.EMPTY_STRING_ARRAY;
        } else {
            return new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
    }

    private class Callback implements BackgroundJob.Callback<Void> {
        @Override
        public void onResult(@NonNull BackgroundJob job, Void result) {
            if (job == receiveFileJob) {
                receiveFileJob = null;
            } else if (job == uploadFileJob) {
                uploadFileJob = null;
            }
        }

        @Override
        public void onError(@NonNull BackgroundJob job, @NonNull Throwable error) {
            if (job == receiveFileJob) {
                receiveFileJob = null;
            } else if (job == uploadFileJob) {
                uploadFileJob = null;
            }
        }
    }

    void cancelJob(long jobId) {
        if (backgroundJobHandler.isRunning(jobId)) {
            BackgroundJob job = backgroundJobHandler.getJob(jobId);

            if (job != null) {
                job.cancel();

                if (job == receiveFileJob) {
                    receiveFileJob = null;
                } else if (job == uploadFileJob) {
                    uploadFileJob = null;
                }
            }
        }
    }

    @Override
    public void onDeviceUnpaired(Context context, String deviceId) {
        Log.i("KDE/SharePlugin", "onDeviceUnpaired deviceId = " + deviceId);
        if (mSharedPrefs == null) {
            mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        }
        mSharedPrefs.edit().remove(KEY_UNREACHABLE_URL_LIST + deviceId).apply();
    }
}

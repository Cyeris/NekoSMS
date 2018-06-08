package com.crossbowffs.nekosms.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.widget.Toast;
import com.crossbowffs.nekosms.R;
import com.crossbowffs.nekosms.consts.BroadcastConsts;
import com.crossbowffs.nekosms.consts.PreferenceConsts;
import com.crossbowffs.nekosms.data.SmsMessageData;
import com.crossbowffs.nekosms.loader.BlockedSmsLoader;
import com.crossbowffs.nekosms.loader.DatabaseException;
import com.crossbowffs.nekosms.loader.InboxSmsLoader;
import com.crossbowffs.nekosms.provider.DatabaseContract;
import com.crossbowffs.nekosms.utils.AppOpsUtils;
import com.crossbowffs.nekosms.utils.Xlog;

public class BlockedSmsReceiver extends BroadcastReceiver {
    private static final String NOTIFICATION_GROUP = "blocked_message";
    public static final String NOTIFICATION_CHANNEL = "blocked_message";

    private static int uriToNotificationId(Uri uri) {
        return (int)ContentUris.parseId(uri);
    }

    private PendingIntent createPendingIntent(Context context, String action, Uri uri) {
        Intent intent = new Intent(context, BlockedSmsReceiver.class);
        intent.setAction(action);
        intent.setData(uri);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    private Notification buildNotificationSingle(Context context, SmsMessageData messageData) {
        Uri uri = ContentUris.withAppendedId(DatabaseContract.BlockedMessages.CONTENT_URI, messageData.getId());

        Intent viewIntent = new Intent(context, MainActivity.class);
        viewIntent.setAction(Intent.ACTION_VIEW);
        viewIntent.setData(uri);
        PendingIntent viewPendingIntent = PendingIntent.getActivity(context, 0, viewIntent, 0);

        PendingIntent deleteIntent = createPendingIntent(context, BroadcastConsts.ACTION_DELETE_SMS, uri);
        PendingIntent restoreIntent = createPendingIntent(context, BroadcastConsts.ACTION_RESTORE_SMS, uri);
        PendingIntent dismissIntent = createPendingIntent(context, BroadcastConsts.ACTION_DISMISS_NOTIFICATION, uri);

        return new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_message_blocked_white_24dp)
            .setContentTitle(context.getString(R.string.format_notification_single_sender, messageData.getSender()))
            .setContentText(messageData.getBody())
            .setStyle(new NotificationCompat.BigTextStyle().bigText(messageData.getBody()))
            .setContentIntent(viewPendingIntent)
            .addAction(R.drawable.ic_delete_white_24dp, context.getString(R.string.delete), deleteIntent)
            .addAction(R.drawable.ic_unarchive_white_24dp, context.getString(R.string.restore), restoreIntent)
            .setDeleteIntent(dismissIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.main))
            .setGroup(NOTIFICATION_GROUP)
            .build();
    }

    private boolean areNotificationsEnabled(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return true;
        }

        SharedPreferences prefs = context.getSharedPreferences(PreferenceConsts.FILE_MAIN, Context.MODE_PRIVATE);
        return prefs.getBoolean(PreferenceConsts.KEY_NOTIFICATIONS_ENABLE, PreferenceConsts.KEY_NOTIFICATIONS_ENABLE_DEFAULT);
    }

    private void applyNotificationStyle(Context context, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PreferenceConsts.FILE_MAIN, Context.MODE_PRIVATE);
        String ringtone = prefs.getString(PreferenceConsts.KEY_NOTIFICATIONS_RINGTONE, PreferenceConsts.KEY_NOTIFICATIONS_RINGTONE_DEFAULT);
        if (!TextUtils.isEmpty(ringtone)) {
            notification.sound = Uri.parse(ringtone);
        }
        if (prefs.getBoolean(PreferenceConsts.KEY_NOTIFICATIONS_VIBRATE, PreferenceConsts.KEY_NOTIFICATIONS_VIBRATE_DEFAULT)) {
            notification.defaults |= Notification.DEFAULT_VIBRATE;
        }
        if (prefs.getBoolean(PreferenceConsts.KEY_NOTIFICATIONS_LIGHTS, PreferenceConsts.KEY_NOTIFICATIONS_LIGHTS_DEFAULT)) {
            notification.defaults |= Notification.DEFAULT_LIGHTS;
        }
        String priority = prefs.getString(PreferenceConsts.KEY_NOTIFICATIONS_PRIORITY, PreferenceConsts.KEY_NOTIFICATIONS_PRIORITY_DEFAULT);
        notification.priority = Integer.parseInt(priority);
    }

    private void removeNotification(Context context, Uri messageUri) {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.cancel(uriToNotificationId(messageUri));
    }

    private void displayNotification(Context context, Uri messageUri) {
        if (!areNotificationsEnabled(context)) {
            BlockedSmsLoader.get().setSeenStatus(context, messageUri, true);
            return;
        }

        SmsMessageData messageData = BlockedSmsLoader.get().query(context, messageUri);
        Notification notification = buildNotificationSingle(context, messageData);
        applyNotificationStyle(context, notification);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.notify(uriToNotificationId(messageUri), notification);
    }

    private void onReceiveSms(Context context, Intent intent) {
        Uri messageUri = intent.getParcelableExtra(BroadcastConsts.EXTRA_MESSAGE);
        if (messageUri == null) {
            return;
        }

        displayNotification(context, messageUri);
    }

    private void onDeleteSms(Context context, Intent intent) {
        Uri messageUri = intent.getData();

        boolean deleted = BlockedSmsLoader.get().delete(context, messageUri);
        if (!deleted) {
            Xlog.e("Failed to delete message: could not load data");
            Toast.makeText(context, R.string.load_message_failed, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, R.string.message_deleted, Toast.LENGTH_SHORT).show();
        }

        removeNotification(context, messageUri);
    }

    private void onRestoreSms(Context context, Intent intent) {
        Uri messageUri = intent.getData();

        BlockedSmsLoader.get().setSeenStatus(context, messageUri, true);
        removeNotification(context, messageUri);

        if (!AppOpsUtils.noteOp(context, AppOpsUtils.OP_WRITE_SMS)) {
            Xlog.e("Do not have permissions to write SMS");
            Toast.makeText(context, R.string.must_enable_xposed_module, Toast.LENGTH_SHORT).show();
            return;
        }

        SmsMessageData messageToRestore = BlockedSmsLoader.get().query(context, messageUri);
        if (messageToRestore == null) {
            Xlog.e("Failed to restore message: could not load data");
            Toast.makeText(context, R.string.load_message_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            InboxSmsLoader.writeMessage(context, messageToRestore);
        } catch (DatabaseException e) {
            Xlog.e("Failed to restore message: could not write to SMS inbox");
            Toast.makeText(context, R.string.message_restore_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        BlockedSmsLoader.get().delete(context, messageUri);
        Toast.makeText(context, R.string.message_restored, Toast.LENGTH_SHORT).show();
    }

    private void onDismissNotification(Context context, Intent intent) {
        Uri messageUri = intent.getData();
        if (messageUri != null) {
            BlockedSmsLoader.get().setSeenStatus(context, messageUri, true);
        } else {
            BlockedSmsLoader.get().markAllSeen(context);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        switch (intent.getAction()) {
        case BroadcastConsts.ACTION_RECEIVE_SMS:
            onReceiveSms(context, intent);
            break;
        case BroadcastConsts.ACTION_DELETE_SMS:
            onDeleteSms(context, intent);
            break;
        case BroadcastConsts.ACTION_RESTORE_SMS:
            onRestoreSms(context, intent);
            break;
        case BroadcastConsts.ACTION_DISMISS_NOTIFICATION:
            onDismissNotification(context, intent);
            break;
        }
    }
}

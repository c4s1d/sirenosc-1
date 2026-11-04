package com.aphidaks.sirenosc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the app alive (and the CPU awake) while audio is playing with the screen off or the app in the background. */
public class PlaybackService extends Service {
    static final String CHANNEL = "playback";
    static final String ACTION_STOP = "com.aphidaks.sirenosc.STOP";
    private static final int NOTIF_ID = 7101;

    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Audio playback", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, PlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Audio running - tap to open")
                .setContentIntent(openPi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat), "Quit", stopPi).build())
                .build();
    }

    private boolean goForeground() {
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Must happen immediately after startForegroundService(), before anything else can fail.
        if (!goForeground()) stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && ACTION_STOP.equals(intent.getAction())) {
                MainActivity.quit();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            goForeground();
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.aphidaks.sirenosc:audio");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(12L * 60L * 60L * 1000L);
            }
        } catch (Throwable t) {
            // never let the helper service take the app down
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }
}

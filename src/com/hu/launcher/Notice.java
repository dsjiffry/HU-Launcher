package com.hu.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * A message from the PC (POST /notify), e.g. "Put the car in reverse now". Posted silently to the
 * notification shade (no sound, no pop-up) and removed after the given time. Empty text removes it.
 */
final class Notice {
    private static final String CHANNEL = "pcmsg";
    private static final int ID = 7301;

    private Notice() { }

    /** Returns "shade" or "hidden". */
    static String show(Context c, String text, int secs) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (text.isEmpty()) { nm.cancel(ID); return "hidden"; }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Messages from PC", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(c, CHANNEL).setTimeoutAfter(Math.max(5, Math.min(3600, secs)) * 1000L);
        } else {
            b = new Notification.Builder(c).setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(R.drawable.ic_pc).setContentTitle("Message from PC").setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setWhen(System.currentTimeMillis()).setShowWhen(true).setAutoCancel(true).setOnlyAlertOnce(true);
        nm.notify(ID, b.build());
        return "shade";
    }
}

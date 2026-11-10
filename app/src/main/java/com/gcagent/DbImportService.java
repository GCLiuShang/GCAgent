package com.gcagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class DbImportService extends Service {
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(42, notification());
        final String account = intent == null ? "default" : intent.getStringExtra("account");
        new Thread(() -> {
            String result;
            try {
                File qdir = new File("/sdcard/Android/data/com.gcagent/files");
                File db = getDatabasePath("gcagent.db");
                String r1 = Db.importContacts(db, account == null ? "default" : account, new File(qdir, "contacts.json"));
                String r2 = Db.importUserQueue(db, account == null ? "default" : account, new File(qdir, "user_queue.jsonl"));
                String r3 = Db.counts(db);
                result = r1 + "\n" + r2 + "\n" + r3 + "\n";
                Xlog.i("DBIMPORT " + result.replace('\n',' '));
            } catch (Throwable t) {
                result = "ERR " + t + "\n";
            }
            try {
                File out = new File("/sdcard/Android/data/com.gcagent/files/db_result.txt");
                out.getParentFile().mkdirs();
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    fo.write(result.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {}
            stopSelf();
        }).start();
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel("gcagent-db") == null) {
            nm.createNotificationChannel(new NotificationChannel("gcagent-db", "GCAgent DB", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, "gcagent-db")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("GCAgent DB import")
                .setContentText("users/chats")
                .build();
    }
}

package com.gcagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class FileNavReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        android.util.Log.i("GCAgent", "FileNavReceiver onReceive");
        if ("com.gcagent.DUMP".equals(intent.getAction()) || intent.getBooleanExtra("dump", false)) {
            FileNavService.log("broadcast dumpVd");
            FileNavService.dumpVd();
            return;
        }
        FileNavService.log("a11y UI actions disabled; only DUMP is supported");
    }
}

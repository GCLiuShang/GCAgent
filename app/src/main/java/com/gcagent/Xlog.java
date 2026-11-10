package com.gcagent;
import de.robv.android.xposed.XposedBridge;
public final class Xlog {
    public static void i(String s) {
        try { XposedBridge.log("GCAgent " + s); } catch (Throwable ignored) {}
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream("/sdcard/Android/data/com.tencent.mobileqq/files/gcagent/gcagent.log", true)) {
            fo.write((System.currentTimeMillis()/1000 + " " + s + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {}
    }
}

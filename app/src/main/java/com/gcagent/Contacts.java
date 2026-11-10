package com.gcagent;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** In-process contacts collector: friend alphabet ListView + troop ExpandableListView. */
public final class Contacts {
    static final String QDIR = "/sdcard/Android/data/com.tencent.mobileqq/files/gcagent";
    static final String CMD = QDIR + "/contacts_cmd.txt";
    static final String OUT = QDIR + "/contacts_out.txt";
    static boolean started;

    public static synchronized void startChannel(final ClassLoader cl) {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            File cmd = new File(CMD);
            File dir = new File(QDIR); dir.mkdirs();
            while (true) {
                try {
                    if (cmd.exists() && cmd.length() > 0) {
                        String s = new String(java.nio.file.Files.readAllBytes(cmd.toPath()), "UTF-8").trim();
                        cmd.delete();
                        if (s.length() > 0) {
                            final String mode = s;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                try {
                                    String r = collectAll(mode);
                                    java.nio.file.Files.write(new File(OUT).toPath(), r.getBytes("UTF-8"));
                                } catch (Throwable e) {
                                    try { java.nio.file.Files.write(new File(OUT).toPath(), ("ERR " + e).getBytes("UTF-8")); } catch (Throwable ignored) {}
                                }
                            });
                        }
                    }
                    Thread.sleep(700);
                } catch (Throwable e) {
                    try { Thread.sleep(1200); } catch (Throwable ignored) {}
                }
            }
        }, "gcagent-contacts");
        t.setDaemon(true); t.start();
        Xlog.i("Contacts channel started");
    }

    public static String collectAll(String mode) {
        List<View> roots = roots();
        List<Friend> friends = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        for (View v : roots) {
            walk(v, friends, groups, new HashSet<View>());
        }
        // dedupe by id
        List<Friend> fu = new ArrayList<>(); Set<String> fs = new HashSet<>();
        for (Friend f : friends) if (f.id != null && fs.add(f.id)) fu.add(f);
        List<Group> gu = new ArrayList<>(); Set<String> gs = new HashSet<>();
        for (Group g : groups) if (g.id != null && gs.add(g.id)) gu.add(g);
        String json = json(fu, gu);
        try {
            File d = new File(QDIR); d.mkdirs();
            java.nio.file.Files.write(new File(QDIR + "/contacts.json").toPath(), json.getBytes("UTF-8"));
        } catch (Throwable e) { Xlog.i("write contacts err " + e); }
        String r = "OK friends=" + fu.size() + " groups=" + gu.size() + " mode=" + mode;
        Xlog.i(r);
        return r;
    }

    static List<View> roots() {
        List<View> out = new ArrayList<>();
        // WindowManagerGlobal.mViews
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            Object v = f.get(inst);
            if (v instanceof List) for (Object o : (List<?>) v) if (o instanceof View) addRoot(out, (View) o);
        } catch (Throwable ignored) {}
        // ActivityThread.mActivities decor
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object th = at.getMethod("currentActivityThread").invoke(null);
            Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object m = f.get(th);
            if (m instanceof java.util.Map) {
                for (Object rec : ((java.util.Map<?,?>) m).values()) {
                    try {
                        Field af = rec.getClass().getDeclaredField("activity");
                        af.setAccessible(true);
                        Object a = af.get(rec);
                        if (a instanceof Activity) {
                            View d = ((Activity) a).getWindow().getDecorView();
                            addRoot(out, d);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static void addRoot(List<View> out, View v) {
        if (v == null) return;
        for (View x : out) if (x == v) return;
        out.add(v);
    }

    static void walk(View v, List<Friend> friends, List<Group> groups, Set<View> seen) {
        if (v == null || !seen.add(v)) return;
        String cn = v.getClass().getName();
        try {
            if (cn.contains("AlphabetPinnedHeaderListView") || cn.contains("HeaderViewListAdapter")) readFriendList(v, friends);
            if (cn.contains("ExpandableListView")) readTroopList(v, groups);
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), friends, groups, seen);
        }
    }

    static void readFriendList(View v, List<Friend> out) {
        Object a = invoke(v, "getAdapter");
        if (a == null) return;
        Object u = a;
        try { Field mf = a.getClass().getDeclaredField("mAdapter"); mf.setAccessible(true); Object x = mf.get(a); if (x != null) u = x; } catch (Throwable ignored) {}
        try {
            Field vf = u.getClass().getDeclaredField("v");
            vf.setAccessible(true);
            Object vv = vf.get(u);
            if (!(vv instanceof List)) return;
            for (Object g : (List<?>) vv) {
                Object fl = field(g, "f");
                if (!(fl instanceof List)) continue;
                for (Object fr : (List<?>) fl) {
                    Friend f = new Friend();
                    f.id = str(field(fr, "uin"));
                    f.uin = f.id;
                    f.nick = str(field(fr, "nick"));
                    f.remark = str(field(fr, "remark"));
                    f.datetime = num(field(fr, "datetime"));
                    f.friendType = num(field(fr, "friendType"));
                    if (f.id != null) out.add(f);
                }
            }
        } catch (Throwable ignored) {}
    }

    static void readTroopList(View v, List<Group> out) {
        Object a = invoke(v, "getExpandableListAdapter");
        if (a == null) return;
        String an = a.getClass().getName();
        if (!an.contains("ContactsTroopAdapter")) return;
        for (String fn : new String[]{"o", "p", "q"}) {
            Object l = field(a, fn);
            if (!(l instanceof List)) continue;
            for (Object w : (List<?>) l) {
                Object ti = field(w, "b");
                if (ti == null) continue;
                Group g = new Group();
                g.id = str(field(ti, "troopuin"));
                g.uin = g.id;
                g.name = str(field(ti, "troopname"));
                g.owner = str(field(ti, "troopowneruin"));
                g.lastMsgTime = num(field(ti, "lastMsgTime"));
                g.groupClassExt = num(field(ti, "dwGroupClassExt"));
                g.groupFlagExt = num(field(ti, "dwGroupFlagExt"));
                if (g.id != null) out.add(g);
            }
        }
    }

    static Object invoke(Object o, String name) {
        try { Method m = o.getClass().getMethod(name); m.setAccessible(true); return m.invoke(o); } catch (Throwable t) { return null; }
    }

    static Object field(Object o, String name) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        while (c != null) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (Throwable ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0L; }

    static String json(List<Friend> fs, List<Group> gs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schema\":1,\"collected_at\":").append(System.currentTimeMillis()/1000);
        sb.append(",\"friend_count\":").append(fs.size()).append(",\"group_count\":").append(gs.size());
        sb.append(",\"friends\":[");
        for (int i=0;i<fs.size();i++) { if(i>0) sb.append(','); Friend f=fs.get(i); sb.append("{\"uin\":").append(q(f.uin)).append(",\"nick\":").append(q(f.nick)).append(",\"remark\":").append(q(f.remark)).append(",\"datetime\":").append(f.datetime).append(",\"friend_type\":").append(f.friendType).append('}'); }
        sb.append("],\"groups\":[");
        for (int i=0;i<gs.size();i++) { if(i>0) sb.append(','); Group g=gs.get(i); sb.append("{\"troopuin\":").append(q(g.uin)).append(",\"troopname\":").append(q(g.name)).append(",\"owner\":").append(q(g.owner)).append(",\"last_msg_time\":").append(g.lastMsgTime).append(",\"class_ext\":").append(g.groupClassExt).append(",\"flag_ext\":").append(g.groupFlagExt).append('}'); }
        sb.append("]}");
        return sb.toString();
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i=0;i<s.length();i++) { char c=s.charAt(i); if(c=='"'||c=='\\') b.append('\\').append(c); else if(c=='\n') b.append("\\n"); else if(c=='\r') b.append("\\r"); else if(c<32) b.append(' '); else b.append(c); }
        return b.append('"').toString();
    }

    static class Friend { String id, uin, nick, remark; long datetime, friendType; }
    static class Group { String id, uin, name, owner; long lastMsgTime, groupClassExt, groupFlagExt; }
}

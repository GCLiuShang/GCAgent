package com.gcagent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.XC_MethodHook;

/**
 * Formal GCAgent entry: QQ-only LSPosed module.
 *
 * Phase 1 responsibilities:
 *  - while QQ is foreground, diff the current chat adapter
 *  - write new messages into QQ's external files /gcagent/outbox
 *  - post batches to the PC receiver and delete after ack
 */
public class GCEntry implements IXposedHookLoadPackage {
    public static Object msgSvc;
    public static String ikMethods() { return ""; }
    public static boolean resolveKernel() { return false; }
    public static void scanSvc(Object o, int d, java.util.IdentityHashMap<Object, Boolean> seen) { }
    public static void callMsgs(String peer, long seq, int count) { }

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!"com.tencent.mobileqq".equals(lp.packageName)) return;
        String proc = processName();
        XposedBridge.log("GCAgent backup module loaded in QQ proc=" + proc);
        if (proc == null || "com.tencent.mobileqq".equals(proc) || proc.endsWith(":peak")) {
            try {
                Collector.start(lp.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("GCAgent start error: " + t);
            }
        }
        if ("com.tencent.mobileqq".equals(proc)) {
            try { Contacts.startChannel(lp.classLoader); } catch (Throwable t) { XposedBridge.log("GCAgent contacts start err: " + t); }
        }
        hookTroopFiles(lp.classLoader);
        hookKuiklyGroupFile(lp.classLoader);
        hookPrivateFileAdapter(lp.classLoader);
        hookDocFileModel(lp.classLoader);
        try {
            Class<?> ca = lp.classLoader.loadClass("com.tencent.mobileqq.activity.ChatActivity");
            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        android.app.Activity a = (android.app.Activity) param.thisObject;
                        Collector.onChatActivity(a);
                    } catch (Throwable t) {
                        XposedBridge.log("GCAgent chat hook err: " + t);
                    }
                }
            };
            String[] names = {"onCreate", "onStart", "onResume", "onWindowFocusChanged", "onNewIntent"};
            for (String n : names) {
                try { XposedBridge.hookAllMethods(ca, n, hook); } catch (Throwable ignored) {}
            }
            XposedBridge.log("GCAgent ChatActivity lifecycle hooks ok");
        } catch (Throwable t) {
            XposedBridge.log("GCAgent ChatActivity hook err: " + t);
        }
        try {
            Class<?> rv = lp.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView");
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(rv, "setAdapter", lp.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$Adapter"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object ad = param.args[0];
                        String an = ad == null ? "" : ad.getClass().getName();
                        XposedBridge.log("GCAgent setAdapter rv=" + param.thisObject.getClass().getName() + " ad=" + an);
                        if (an.contains("msglist") || an.contains("aio.msg") || an.contains("aio.part")) {
                            Collector.onChatRecyclerView((android.view.View) param.thisObject, ad);
                        }
                    } catch (Throwable t) { XposedBridge.log("GCAgent rv hook err: " + t); }
                }
            });
            XposedBridge.log("GCAgent RecyclerView.setAdapter hook ok");
        } catch (Throwable t) {
            XposedBridge.log("GCAgent rv hook err: " + t);
        }
        try {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callActivityOnResume", android.app.Activity.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        android.app.Activity a = (android.app.Activity) param.args[0];
                        if (a.getClass().getName().contains("ChatActivity")) {
                            Collector.onChatActivity(a);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("GCAgent instr hook err: " + t);
                    }
                }
            });
            XposedBridge.log("GCAgent Instrumentation hook ok");
        } catch (Throwable t) {
            XposedBridge.log("GCAgent Instrumentation hook err: " + t);
        }
    }

    private static String processName() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object n = at.getMethod("currentProcessName").invoke(null);
            if (n != null) return String.valueOf(n);
        } catch (Throwable ignored) {}
        try {
            return android.app.Application.getProcessName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void hookTroopFiles(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("com.tencent.mobileqq.troop.file.data.TroopFileShowAdapter");
            XC_MethodHook h = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object l = param.args[0];
                        if (!(l instanceof java.util.List)) return;
                        java.util.List<?> list = (java.util.List<?>) l;
                        StringBuilder sb = new StringBuilder("TROOPFILE list size=" + list.size());
                        int n = Math.min(3, list.size());
                        for (int i = 0; i < n; i++) {
                            Object o = list.get(i);
                            sb.append("\n  [").append(i).append("] ").append(o == null ? "null" : o.getClass().getName());
                            if (o != null) {
                                java.lang.reflect.Field[] fs = o.getClass().getDeclaredFields();
                                int k = 0;
                                for (java.lang.reflect.Field f : fs) {
                                    if (k++ > 18) break;
                                    try { f.setAccessible(true); Object v = f.get(o); sb.append(" ").append(f.getName()).append("=").append(v); } catch (Throwable ignored) {}
                                }
                            }
                        }
                        dlog(sb.toString());
                    } catch (Throwable t) { dlog("TROOPFILE dump err " + t); }
                }
            };
            try { de.robv.android.xposed.XposedHelpers.findAndHookMethod(c, "g", java.util.List.class, boolean.class, boolean.class, h); dlog("TROOPFILE hook g ok"); } catch (Throwable t) { dlog("TROOPFILE g err " + t); }
            try { de.robv.android.xposed.XposedHelpers.findAndHookMethod(c, "V9", java.util.List.class, boolean.class, String.class, long.class, java.util.ArrayList.class, h); dlog("TROOPFILE hook V9 ok"); } catch (Throwable t) { dlog("TROOPFILE V9 err " + t); }
        } catch (Throwable t) { dlog("TROOPFILE class err " + t); }
    }

    static void dlog(String s) {
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream("/sdcard/Android/data/com.tencent.mobileqq/files/gc_dl.txt", true)) {
            fo.write((s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
        XposedBridge.log("GCAgent " + s);
    }

    static void hookKuiklyGroupFile(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("com.tencent.mobileqq.troop.file.main.ntcompose.modules.GroupFileListModule");
            de.robv.android.xposed.XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    final Object inst = param.thisObject;
                    new Thread(() -> {
                        try { Thread.sleep(6000); } catch (Throwable ignored) {}
                        try {
                            StringBuilder sb = new StringBuilder("KUIKLY GroupFileListModule " + inst.getClass().getName());
                            for (String fn : new String[]{"d","e","i"}) {
                                try {
                                    java.lang.reflect.Field f = inst.getClass().getDeclaredField(fn);
                                    f.setAccessible(true);
                                    Object v = f.get(inst);
                                    sb.append("\n ").append(fn).append("=").append(v == null ? "null" : v.getClass().getName());
                                    if (v instanceof java.util.Map) {
                                        java.util.Map<?,?> m = (java.util.Map<?,?>) v;
                                        sb.append(" size=").append(m.size());
                                        int k = 0;
                                        for (java.util.Map.Entry<?,?> en : m.entrySet()) {
                                            if (k++ >= 5) break;
                                            sb.append("\n   ").append(en.getKey()).append(" -> ").append(en.getValue());
                                        }
                                    }
                                } catch (Throwable t) { sb.append("\n ").append(fn).append(" err ").append(t); }
                            }
                            dlog(sb.toString());
                        } catch (Throwable t) { dlog("KUIKLY err " + t); }
                    }, "gc-kuiikly-dump").start();
                }
            });
            dlog("KUIKLY ctor hook ok");
        } catch (Throwable t) { dlog("KUIKLY hook err " + t); }
    }

    static void hookPrivateFileAdapter(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("com.tencent.qqnt.chathistory.ui.file.c2c.adapter.c");
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(c, "e0", java.util.List.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object l = param.args[0];
                        if (!(l instanceof java.util.List)) return;
                        java.util.List<?> list = (java.util.List<?>) l;
                        StringBuilder sb = new StringBuilder("PRIVFILE e0 size=" + list.size());
                        int n = Math.min(3, list.size());
                        for (int i = 0; i < n; i++) {
                            Object o = list.get(i);
                            sb.append("\n  [").append(i).append("] ").append(o == null ? "null" : o.getClass().getName());
                            if (o != null) dumpObj(sb, o, 0);
                        }
                        dlog(sb.toString());
                    } catch (Throwable t) { dlog("PRIVFILE err " + t); }
                }
            });
            dlog("PRIVFILE hook e0 ok");
        } catch (Throwable t) { dlog("PRIVFILE hook err " + t); }
    }

    static void hookDocFileModel(ClassLoader cl) {
        try {
            Class<?> a = cl.loadClass("com.tencent.qqnt.chathistory.ui.document.adapter.a");
            de.robv.android.xposed.XposedBridge.hookAllConstructors(a, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    StringBuilder sb = new StringBuilder("DOCADAPTER ctor " + param.thisObject.getClass().getName());
                    dumpObj(sb, param.thisObject, 0);
                    dlog(sb.toString());
                }
            });
            dlog("DOCADAPTER hook ok");
        } catch (Throwable t) { dlog("DOCADAPTER hook err " + t); }
        try {
            Class<?> m = cl.loadClass("com.tencent.qqnt.chathistory.ui.document.data.model.b");
            de.robv.android.xposed.XposedBridge.hookAllConstructors(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object rec = param.args.length > 0 ? param.args[0] : null;
                        StringBuilder sb = new StringBuilder("DOCMODEL " + (rec == null ? "null" : rec.getClass().getName()));
                        if (rec != null) {
                            sb.append(" msgId=").append(getField(rec, "msgId"));
                            sb.append(" msgSeq=").append(getField(rec, "msgSeq"));
                            sb.append(" time=").append(getField(rec, "msgTime"));
                            Object els = getField(rec, "elements");
                            if (els instanceof java.util.List) {
                                int i = 0;
                                for (Object el : (java.util.List<?>) els) {
                                    Object inner = unwrapEl(el);
                                    String cn = inner == null ? "null" : inner.getClass().getName();
                                    sb.append("\n   el").append(i++).append(" ").append(cn);
                                    if (cn.contains("FileElement")) {
                                        sb.append(" uuid=").append(getField(inner, "fileUuid"));
                                        sb.append(" name=").append(getField(inner, "fileName"));
                                        sb.append(" size=").append(getField(inner, "fileSize"));
                                    }
                                }
                            }
                        }
                        dlog(sb.toString());
                    } catch (Throwable t) { dlog("DOCMODEL err " + t); }
                }
            });
            dlog("DOCMODEL hook ok");
        } catch (Throwable t) { dlog("DOCMODEL hook err " + t); }
    }

    static Object getField(Object o, String name) {
        try {
            java.lang.reflect.Field f = o.getClass().getField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable ignored) { return null; }
    }

    static Object unwrapEl(Object el) {
        if (el == null) return null;
        String cn = el.getClass().getName();
        if (!cn.endsWith("MsgElement")) return el;
        for (java.lang.reflect.Field f : el.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object v = f.get(el);
                if (v == null) continue;
                String vc = v.getClass().getName();
                if (vc.contains("FileElement") || vc.contains("TextElement") || vc.contains("PicElement")) return v;
            } catch (Throwable ignored) {}
        }
        return el;
    }

    static void hookGroupModuleData(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("com.tencent.mobileqq.troop.file.main.ntcompose.modules.GroupFileListModule");
            for (String name : new String[]{"A","B","F","H"}) {
                final String mn = name;
                try {
                    de.robv.android.xposed.XposedBridge.hookAllMethods(c, mn, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object r = param.getResult();
                                StringBuilder sb = new StringBuilder("GRPFILE " + mn + " ret=" + (r == null ? "null" : r.getClass().getName()));
                                if (r instanceof java.util.Map) {
                                    java.util.Map<?,?> m = (java.util.Map<?,?>) r;
                                    sb.append(" size=").append(m.size());
                                    int k = 0;
                                    for (java.util.Map.Entry<?,?> en : m.entrySet()) {
                                        if (k++ >= 5) break;
                                        sb.append("\n   ").append(en.getKey()).append(" -> ").append(en.getValue());
                                    }
                                }
                                dlog(sb.toString());
                            } catch (Throwable t) { dlog("GRPFILE dump err " + t); }
                        }
                    });
                } catch (Throwable t) { dlog("GRPFILE " + name + " err " + t); }
            }
            try {
                Class<?> api = cl.loadClass("com.tencent.mobileqq.troop.file.api.impl.GroupFileKuiklyApiImpl");
                for (String mn2 : new String[]{"registerExternalModule", "registerTDFlModule"}) {
                    de.robv.android.xposed.XposedBridge.hookAllMethods(api, mn2, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object a = param.args.length > 0 ? param.args[0] : null;
                                StringBuilder sb = new StringBuilder("GRPREG " + param.args.length + " arg=" + (a == null ? "null" : a.getClass().getName()));
                                if (a != null) dumpObj(sb, a, 0);
                                dlog(sb.toString());
                            } catch (Throwable t) { dlog("GRPREG err " + t); }
                        }
                    });
                }
                dlog("GRPREG hooks ok");
            } catch (Throwable t) { dlog("GRPREG err " + t); }
            dlog("GRPFILE hooks ok");
        } catch (Throwable t) { dlog("GRPFILE hook err " + t); }
    }

    static void dumpObj(StringBuilder sb, Object o, int depth) {
        java.lang.reflect.Field[] fs = o.getClass().getDeclaredFields();
        int k = 0;
        for (java.lang.reflect.Field f : fs) {
            if (k++ > 16) break;
            try {
                f.setAccessible(true);
                Object v = f.get(o);
                sb.append(" ").append(f.getName()).append("=").append(v);
            } catch (Throwable ignored) {}
        }
    }

    static void hookKuiklyFragment(ClassLoader cl) {
        try {
            Class<?> f = cl.loadClass("com.tencent.mobileqq.ntcompose.container.QQKuiklyFragment");
            de.robv.android.xposed.XposedBridge.hookAllConstructors(f, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    StringBuilder sb = new StringBuilder("KFRAG ctor " + param.thisObject.getClass().getName());
                    dumpObj(sb, param.thisObject, 0);
                    dlog(sb.toString());
                }
            });
            dlog("KFRAG ctor hook ok");
        } catch (Throwable t) { dlog("KFRAG ctor err " + t); }
        try {
            Class<?> u = cl.loadClass("com.tencent.mobileqq.ntcompose.utils.QQKuiklyCommUtil$Companion");
            de.robv.android.xposed.XposedBridge.hookAllMethods(u, "b", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        StringBuilder sb = new StringBuilder("KUTIL b ret=" + (param.getResult() == null ? "null" : param.getResult().getClass().getName()));
                        if (param.getResult() != null) dumpObj(sb, param.getResult(), 0);
                        if (param.args.length > 0) {
                            Object a = param.args[0];
                            sb.append(" arg=").append(a == null ? "null" : a.getClass().getName());
                            if (a != null) dumpObj(sb, a, 0);
                        }
                        dlog(sb.toString());
                    } catch (Throwable t) { dlog("KUTIL err " + t); }
                }
            });
            dlog("KUTIL hook ok");
        } catch (Throwable t) { dlog("KUTIL hook err " + t); }
    }

    static void hookTroopRepo(ClassLoader cl) {
        try {
            Class<?> r = cl.loadClass("com.tencent.mobileqq.troop.file.main.repo.TroopFileMainRepo");
            de.robv.android.xposed.XposedBridge.hookAllConstructors(r, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    StringBuilder sb = new StringBuilder("TREPO ctor " + param.thisObject.getClass().getName());
                    dumpObj(sb, param.thisObject, 0);
                    dlog(sb.toString());
                }
            });
            dlog("TREPO ctor hook ok");
        } catch (Throwable t) { dlog("TREPO ctor err " + t); }
        try {
            Class<?> lc = cl.loadClass("androidx.collection.LruCache");
            de.robv.android.xposed.XposedBridge.hookAllMethods(lc, "put", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object k = param.args.length > 0 ? param.args[0] : null;
                        Object v = param.args.length > 1 ? param.args[1] : null;
                        String vc = v == null ? "null" : v.getClass().getName();
                        String kc = k == null ? "null" : k.toString();
                        if (vc.toLowerCase().contains("file") || kc.toLowerCase().contains("file") || kc.toLowerCase().contains("test")) {
                            StringBuilder sb = new StringBuilder("TREPO put key=" + kc + " val=" + vc);
                            if (v != null) dumpObj(sb, v, 0);
                            dlog(sb.toString());
                        }
                    } catch (Throwable ignored) {}
                }
            });
            dlog("TREPO lru hook ok");
        } catch (Throwable t) { dlog("TREPO lru err " + t); }
    }
}

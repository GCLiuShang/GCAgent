package com.gcagent;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Async metadata/file-link sync: pushes completed media tasks to the PC. */
public class Sync {
    static final Set<String> sent = new HashSet<String>();
    static boolean started = false;

    static synchronized void start(final Tasks tasks, final File dir) {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try { flush(tasks, dir); } catch (Throwable ignored) {}
                    try { Thread.sleep(10000); } catch (Throwable ignored) {}
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    static synchronized void flush(Tasks tasks, File dir) throws Exception {
        List<Tasks.Task> all = tasks.doneMedia();
        List<Tasks.Task> todo = new ArrayList<Tasks.Task>();
        for (Tasks.Task t : all) if (!sent.contains(t.id)) todo.add(t);
        if (todo.isEmpty()) return;
        String host = "127.0.0.1";
        int port = 8765;
        try {
            String cfg = new String(java.nio.file.Files.readAllBytes(new File(dir, "config.json").toPath()), StandardCharsets.UTF_8);
            host = str(cfg, "host", host);
            port = (int) num(cfg, "port", port);
        } catch (Throwable ignored) {}
        if (port <= 0) return;
        Socket s = new Socket(host, port);
        s.setSoTimeout(5000);
        try {
            StringBuilder up = new StringBuilder();
            for (Tasks.Task t : todo) {
                if (up.length() > 0) up.append(",");
                up.append("{\"peer_uid\":\"").append(esc(t.peer)).append("\",")
                  .append("\"msg_id\":\"").append(esc(t.msgId)).append("\",")
                  .append("\"content_key\":\"").append(esc(t.contentKey)).append("\",")
                  .append("\"file_name\":\"").append(esc(t.fileName)).append("\",")
                  .append("\"file_size\":").append(t.fileSize).append(",")
                  .append("\"local_path\":\"").append(esc(t.localPath)).append("\",")
                  .append("\"state\":1}");
            }
            String env = "{\"v\":1,\"type\":\"media_update\",\"batch_id\":\"m" + System.currentTimeMillis()
                    + "\",\"account\":\"default\",\"updates\":[" + up + "]}";
            byte[] b = env.getBytes(StandardCharsets.UTF_8);
            OutputStream os = s.getOutputStream();
            os.write(new byte[]{(byte)(b.length>>>24),(byte)(b.length>>>16),(byte)(b.length>>>8),(byte)b.length});
            os.write(b); os.flush();
            InputStream is = s.getInputStream();
            byte[] h = new byte[4];
            int r = is.read(h);
            if (r == 4) {
                int len = ((h[0]&255)<<24)|((h[1]&255)<<16)|((h[2]&255)<<8)|(h[3]&255);
                byte[] a = new byte[len]; int off = 0;
                while (off < len) { int n = is.read(a, off, len-off); if (n < 0) break; off += n; }
                for (Tasks.Task t : todo) sent.add(t.id);
            }
        } finally { s.close(); }
    }

    static String str(String s, String k, String d) {
        String q = "\"" + k + "\"";
        int i = s.indexOf(q); if (i < 0) return d;
        i = s.indexOf(':', i); if (i < 0) return d;
        int a = s.indexOf('"', i + 1); if (a < 0) return d;
        int b = s.indexOf('"', a + 1); if (b < 0) return d;
        return s.substring(a + 1, b);
    }

    static long num(String s, String k, long d) {
        String q = "\"" + k + "\"";
        int i = s.indexOf(q); if (i < 0) return d;
        i = s.indexOf(':', i); if (i < 0) return d;
        int a = i + 1; while (a < s.length() && !Character.isDigit(s.charAt(a))) a++;
        int b = a; while (b < s.length() && Character.isDigit(s.charAt(b))) b++;
        try { return Long.parseLong(s.substring(a, b)); } catch (Throwable t) { return d; }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

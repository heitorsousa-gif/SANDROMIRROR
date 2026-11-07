package br.heitor.carlink;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Registro simples em memória e em arquivo, para o usuário copiar e mandar. */
final class AppLog {

    interface Listener {
        void onLog(String line);
    }

    private static final int MAX_CHARS = 300_000;
    private static final StringBuilder BUF = new StringBuilder();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile Listener listener;
    private static File file;

    private AppLog() {
    }

    static synchronized void init(Context c) {
        if (file != null) return;
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        file = new File(dir, "carlink-log.txt");
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static void i(String msg) {
        android.util.Log.i("SandroMirror", msg);
        String line;
        synchronized (AppLog.class) {
            line = TIME.format(new Date()) + "  " + msg;
            BUF.append(line).append('\n');
            if (BUF.length() > MAX_CHARS) BUF.delete(0, BUF.length() - (MAX_CHARS * 3 / 4));
            if (file != null) {
                try (FileWriter w = new FileWriter(file, true)) {
                    w.write(line);
                    w.write('\n');
                } catch (IOException ignored) {
                }
            }
        }
        final Listener l = listener;
        if (l != null) {
            final String out = line;
            MAIN.post(() -> l.onLog(out));
        }
    }

    static synchronized String text() {
        return BUF.toString();
    }

    static synchronized void clear() {
        BUF.setLength(0);
        if (file != null) file.delete();
    }

    static String hex(byte[] b, int len, int max) {
        int n = Math.min(len, max);
        StringBuilder sb = new StringBuilder(n * 3 + 40);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
            if (i + 1 < n) sb.append(' ');
        }
        if (len > max) sb.append(" ...");
        sb.append("  |");
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            sb.append(c >= 32 && c < 127 ? (char) c : '.');
        }
        sb.append('|');
        return sb.toString();
    }
}

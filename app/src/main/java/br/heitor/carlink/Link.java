package br.heitor.carlink;

import android.content.Context;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Conexão USB com a central no modo Acessório, falando o mesmo protocolo do Hi-Link
 * (veja {@link Proto}).
 */
final class Link {

    interface Listener {
        void onStateChanged();

        /** A central pediu para começar o espelhamento. */
        void onStartRequested();
    }

    enum State { DESCONECTADO, CONECTADO, SINCRONIZADO, ESPELHANDO }

    private static final Object WRITE_LOCK = new Object();
    private static ParcelFileDescriptor pfd;
    private static FileInputStream in;
    private static FileOutputStream out;
    private static volatile State state = State.DESCONECTADO;
    private static volatile Listener listener;

    /** Parâmetros pedidos pela central no STAR. */
    static volatile int reqWidth = 800;
    static volatile int reqHeight = 480;
    static volatile int reqBitrate = 4_000_000;
    static volatile boolean startRequested;
    /** A central pediu pausa no vídeo (VCTL = 1). */
    static volatile boolean videoPaused;
    /** Reenviar SPS/PPS antes do próximo quadro (depois de um VCTL de retomada). */
    static volatile boolean resendConfig;
    /** Tamanho real da tela do celular (maior lado primeiro), enviado na resposta do STAR. */
    static volatile int screenLong = 1600;
    static volatile int screenShort = 720;

    static volatile long rxBytes;
    static volatile long txBytes;

    private Link() {
    }

    static State state() {
        return state;
    }

    static boolean isConnected() {
        return state != State.DESCONECTADO;
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static void setState(State s) {
        if (state == s) return;
        state = s;
        AppLog.i("Estado: " + s);
        Listener l = listener;
        if (l != null) l.onStateChanged();
    }

    static synchronized boolean open(Context c, UsbAccessory acc) {
        if (isConnected()) {
            AppLog.i("Já conectado.");
            return true;
        }
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        ParcelFileDescriptor p;
        try {
            p = um.openAccessory(acc);
        } catch (Exception e) {
            AppLog.i("ERRO ao abrir o acessório: " + e);
            return false;
        }
        if (p == null) {
            AppLog.i("ERRO: openAccessory devolveu null (outro app pode estar com a conexão, como o Hi-Link).");
            return false;
        }
        pfd = p;
        FileDescriptor fd = p.getFileDescriptor();
        in = new FileInputStream(fd);
        out = new FileOutputStream(fd);
        rxBytes = 0;
        txBytes = 0;
        startRequested = false;
        videoPaused = false;
        setState(State.CONECTADO);
        Thread t = new Thread(Link::readLoop, "usb-reader");
        t.setPriority(Thread.MAX_PRIORITY);
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void readLoop() {
        byte[] b = new byte[102400];
        long heartbeats = 0;
        long lastSummary = SystemClock.elapsedRealtime();
        FileInputStream input = in;
        try {
            while (isConnected()) {
                int n = input.read(b, 0, b.length);
                if (n < 0) {
                    AppLog.i("A central fechou a conexão.");
                    break;
                }
                if (n == 0) continue;
                rxBytes += n;
                if (n == 4) {
                    // Batimento: devolve os mesmos 4 bytes, como o Hi-Link faz.
                    heartbeats++;
                    write(b, 0, 4);
                } else {
                    handle(b, n);
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastSummary >= 10_000) {
                    AppLog.i("Batimentos nos últimos 10s: " + heartbeats + " | enviado " + (txBytes / 1024) + " KB no total");
                    heartbeats = 0;
                    lastSummary = now;
                }
            }
        } catch (IOException e) {
            if (isConnected()) AppLog.i("Leitura encerrada: " + e.getMessage());
        } finally {
            close();
        }
    }

    private static void handle(byte[] b, int n) throws IOException {
        Proto.Msg m = Proto.parse(b, n);
        if (m == null) {
            AppLog.i("RX ignorado (" + n + " bytes): " + AppLog.hex(b, n, 32));
            return;
        }
        switch (m.cmd) {
            case Proto.SYNC: {
                AppLog.i("RX SYNC (seq " + Integer.toHexString(m.seq) + ") -> respondendo SYNC");
                write(new Proto.Builder(Proto.SYNC, m.seq).i32(2).build());
                setState(State.SINCRONIZADO);
                break;
            }
            case Proto.STAR: {
                int w = m.i16(20);
                int h = m.i16(22);
                int br = m.i32(24);
                int extra = m.i32(28);
                AppLog.i("RX STAR: " + w + "x" + h + ", bitrate " + br + ", extra " + extra);
                if (w > 0 && h > 0) {
                    reqWidth = w;
                    reqHeight = h;
                    if (br > 0) reqBitrate = br;
                } else {
                    reqWidth = 1024;
                    reqHeight = 576;
                    reqBitrate = 7_000_000;
                }
                videoPaused = false;
                write(new Proto.Builder(Proto.STAR, 0)
                        .i16(reqWidth).i16(reqHeight).i32(reqBitrate)
                        .i16(screenLong).i16(screenShort)
                        .i32(0).i32(0).build());
                AppLog.i("TX STAR: vídeo " + reqWidth + "x" + reqHeight + ", tela " + screenLong + "x" + screenShort);
                startRequested = true;
                Listener l = listener;
                if (l != null) l.onStartRequested();
                break;
            }
            case Proto.LIVE: {
                write(new Proto.Builder(Proto.LIVE, m.seq).i16(0).build());
                break;
            }
            case Proto.VCTL: {
                boolean pause = m.u8(20) != 0;
                if (pause != videoPaused) AppLog.i("RX VCTL: vídeo " + (pause ? "PAUSADO" : "RETOMADO") + " pela central");
                videoPaused = pause;
                if (!pause) resendConfig = true;
                break;
            }
            default:
                AppLog.i("RX comando desconhecido \"" + m.cmd + "\" (" + n + " bytes): " + AppLog.hex(b, Math.min(n, 64), 64));
        }
    }

    static void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    static void write(byte[] data, int off, int len) throws IOException {
        FileOutputStream o = out;
        if (!isConnected() || o == null) throw new IOException("sem conexão com a central");
        synchronized (WRITE_LOCK) {
            o.write(data, off, len);
        }
        txBytes += len;
    }

    static synchronized void close() {
        if (state == State.DESCONECTADO && pfd == null) return;
        state = State.DESCONECTADO;
        try {
            if (pfd != null) pfd.close();
        } catch (IOException ignored) {
        }
        pfd = null;
        in = null;
        out = null;
        startRequested = false;
        AppLog.i("Desconectado. Recebido: " + rxBytes + " bytes, enviado: " + txBytes + " bytes.");
        Listener l = listener;
        if (l != null) l.onStateChanged();
    }
}

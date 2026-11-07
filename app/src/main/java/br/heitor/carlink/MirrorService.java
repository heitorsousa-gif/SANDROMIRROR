package br.heitor.carlink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.view.Display;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Guarda a permissão de captura de tela e, quando a central manda STAR, grava a tela
 * em H.264 e envia em mensagens VDAT, do mesmo jeito que o Hi-Link.
 */
public class MirrorService extends Service {

    static final String ACTION_START = "br.heitor.carlink.START";
    static final String ACTION_STOP = "br.heitor.carlink.STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";

    private static final String CHANNEL = "mirror";
    private static final int NOTIF_ID = 1;

    /** Serviço ativo com permissão de captura. */
    static volatile boolean running;

    private MediaProjection projection;
    private VirtualDisplay display;
    private Thread worker;
    private volatile boolean stop;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), intent.getParcelableExtra(EXTRA_DATA));
        if (projection == null) {
            AppLog.i("ERRO: permissão de captura de tela inválida.");
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                AppLog.i("Captura de tela encerrada pelo sistema.");
                shutdown();
                stopSelf();
            }
        }, new Handler(Looper.getMainLooper()));

        running = true;
        stop = false;
        AppLog.i("Captura de tela autorizada. Esperando a central pedir o vídeo (STAR)...");
        worker = new Thread(this::loop, "mirror");
        worker.start();
        return START_NOT_STICKY;
    }

    private void loop() {
        while (!stop) {
            if (Link.isConnected() && Link.needEncoder) {
                Link.needEncoder = false;
                try {
                    encodeSession(Link.reqWidth, Link.reqHeight, Link.reqBitrate);
                } catch (Exception e) {
                    if (!stop) AppLog.i("Sessão de vídeo terminou: " + e);
                }
                if (Link.isConnected() && !stop) Link.setState(Link.State.SINCRONIZADO);
            } else {
                SystemClock.sleep(150);
            }
        }
    }

    private void encodeSession(int w, int h, int bitrate) throws IOException {
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, clampBitrate(codec, bitrate));
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 30);
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0); // tempo real: o codificador tem prioridade
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface surface = codec.createInputSurface();
            codec.start();

            int dpi = getResources().getDisplayMetrics().densityDpi;
            if (display == null) {
                display = projection.createVirtualDisplay("carlink", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
            } else {
                display.resize(w, h, dpi);
                display.setSurface(surface);
            }
            Link.setState(Link.State.ESPELHANDO);
            AppLog.i("Vídeo iniciado: " + w + "x" + h + ", codificador " + codec.getName());

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] config = null;
            long lastSync = 0;
            long frames = 0;
            long bytes = 0;
            long recoveries = 0;
            long lastLog = SystemClock.elapsedRealtime();
            // Depois de qualquer quadro perdido (pausa da central, câmera de ré...), os quadros
            // seguintes dependem do que se perdeu e aparecem quebrados. Então: pede um
            // quadro-chave na hora e só volta a mandar a partir dele.
            boolean waitKey = true;
            boolean wasPaused = false;
            // Sai deste laço só quando a resolução muda (needEncoder). STAR repetido com a mesma
            // resolução NÃO recria o codificador: vira só um pedido de quadro-chave (needKeyframe).
            while (!stop && Link.isConnected() && !Link.needEncoder) {
                int idx = codec.dequeueOutputBuffer(info, 100_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    config = concat(of.getByteBuffer("csd-0"), of.getByteBuffer("csd-1"));
                    AppLog.i("SPS/PPS (" + config.length + " bytes) enviado.");
                    sendVideo(config, 0, config.length);
                    requestSync(codec);
                    lastSync = SystemClock.elapsedRealtime();
                    continue;
                }
                if (idx < 0) continue;
                ByteBuffer ob = codec.getOutputBuffer(idx);
                boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                boolean paused = Link.videoPaused;
                if (paused && !wasPaused) AppLog.i("Central pausou o vídeo (câmera de ré ou tela própria).");
                if (!paused && wasPaused) {
                    AppLog.i("Central retomou o vídeo: mandando imagem completa.");
                    waitKey = true;
                    Link.resendConfig = true;
                    requestSync(codec);
                    lastSync = SystemClock.elapsedRealtime();
                }
                wasPaused = paused;
                // A central mandou STAR de novo (ressincronizar): manda um quadro-chave já.
                if (Link.needKeyframe) {
                    Link.needKeyframe = false;
                    waitKey = true;
                    requestSync(codec);
                    lastSync = SystemClock.elapsedRealtime();
                }

                if (!isConfig && ob != null && info.size > 0 && config != null) {
                    if (paused) {
                        waitKey = true;
                    } else if (waitKey && !isKey) {
                        // descarta até chegar o quadro-chave pedido
                    } else {
                        if (waitKey || Link.resendConfig) {
                            Link.resendConfig = false;
                            sendVideo(config, 0, config.length);
                            if (waitKey) recoveries++;
                            waitKey = false;
                        }
                        byte[] data = new byte[info.size];
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.get(data);
                        sendVideo(data, 0, data.length);
                        frames++;
                        bytes += data.length;
                    }
                }
                codec.releaseOutputBuffer(idx, false);
                long now = SystemClock.elapsedRealtime();
                // Quadro-chave a cada 1 s (o Hi-Link usa 0,5 s): equilíbrio entre qualidade
                // e recuperação rápida se a central perder algum pedaço.
                if (now - lastSync > 1000 || (waitKey && now - lastSync > 300)) {
                    requestSync(codec);
                    lastSync = now;
                }
                if (now - lastLog >= 10_000) {
                    AppLog.i("Vídeo: " + (frames / 10) + " quadros/s, " + (bytes / 10240) + " KB/s, "
                            + recoveries + " recuperações" + (paused ? " (pausado pela central)" : ""));
                    frames = 0;
                    bytes = 0;
                    recoveries = 0;
                    lastLog = now;
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
        }
    }

    private void sendVideo(byte[] data, int off, int len) throws IOException {
        byte[] msg = new Proto.Builder(Proto.VDAT, 0)
                .i32((int) System.currentTimeMillis())
                .i32(rotation())
                .i32(len)
                .bytes(data, off, len)
                .build();
        Link.write(msg);
    }

    private int rotation() {
        Display d = ((DisplayManager) getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
        switch (d == null ? 0 : d.getRotation()) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private static void requestSync(MediaCodec codec) {
        Bundle p = new Bundle();
        p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        codec.setParameters(p);
    }

    private static int clampBitrate(MediaCodec codec, int bitrate) {
        try {
            Range<Integer> r = codec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .getVideoCapabilities().getBitrateRange();
            return Math.max(r.getLower(), Math.min(r.getUpper(), bitrate));
        } catch (Exception e) {
            return bitrate;
        }
    }

    private static byte[] concat(ByteBuffer a, ByteBuffer b) {
        int la = a == null ? 0 : a.remaining();
        int lb = b == null ? 0 : b.remaining();
        byte[] r = new byte[la + lb];
        if (a != null) a.duplicate().get(r, 0, la);
        if (b != null) b.duplicate().get(r, la, lb);
        return r;
    }

    private synchronized void shutdown() {
        stop = true;
        boolean was = running;
        running = false;
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        worker = null;
        if (display != null) {
            display.release();
            display = null;
        }
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            p.stop();
        }
        if (was) AppLog.i("Espelhamento desligado.");
        Overlay.stop(this);
        if (Link.isConnected()) Link.setState(Link.State.SINCRONIZADO);
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Espelhamento", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return b.setContentTitle("CarLink")
                .setContentText("Espelhando a tela para a central")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}

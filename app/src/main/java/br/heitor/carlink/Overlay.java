package br.heitor.carlink;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * Botão azul flutuante para voltar ao painel.
 * Modo central: fica no meio da esquerda e a janela pede orientação deitada, o que deixa
 * os outros apps (Mapa, YouTube...) em paisagem na central.
 * Modo celular: vai para o canto de baixo à direita e não força orientação.
 * A janela também mantém a tela acesa enquanto espelha. Precisa da permissão de sobreposição.
 */
public class Overlay extends Service {

    static final String ACTION_REFRESH = "br.heitor.carlink.OVERLAY_REFRESH";

    private WindowManager wm;
    private ImageView button;
    private WindowManager.LayoutParams params;

    static void start(Context c) {
        if (Settings.canDrawOverlays(c)) c.startService(new Intent(c, Overlay.class));
        else AppLog.i("Sem permissão de sobreposição: botão flutuante e paisagem automática desligados.");
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, Overlay.class));
    }

    /** Reaplica posição, orientação e "apagar tela" depois de mudar algum ajuste. */
    static void refresh(Context c) {
        if (MirrorService.running && Settings.canDrawOverlays(c)) {
            c.startService(new Intent(c, Overlay.class).setAction(ACTION_REFRESH));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) return START_NOT_STICKY;
        if (button != null) {
            if (intent != null && ACTION_REFRESH.equals(intent.getAction())) applyMode();
            return START_NOT_STICKY;
        }
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        float d = getResources().getDisplayMetrics().density;
        int size = Math.round(72 * d);
        button = new ImageView(this);
        button.setImageResource(R.drawable.ic_home);
        button.setColorFilter(Color.WHITE);
        int pad = Math.round(18 * d);
        button.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(210, 21, 101, 192));
        bg.setStroke(Math.round(2 * d), Color.argb(200, 0, 229, 255));
        button.setBackground(bg);
        button.setOnClickListener(v -> DashActivity.open(this));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(size, size, type,
                // KEEP_SCREEN_ON: enquanto espelha, a tela do celular não apaga sozinha
                // (o espelhamento precisa da tela ligada).
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        setModeParams();
        try {
            wm.addView(button, params);
        } catch (Exception e) {
            AppLog.i("Não consegui criar o botão flutuante: " + e.getMessage());
            button = null;
        }
        return START_NOT_STICKY;
    }

    private void setModeParams() {
        float d = getResources().getDisplayMetrics().density;
        // "Apagar tela" também nesta janela, que fica por cima de qualquer app: assim o brilho
        // mínimo vale no Maps, no Spotify etc., não só nas telas do SandroMirror.
        params.screenBrightness = Prefs.apagarTela(this) ? 0.01f
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (Prefs.modoCelular(this)) {
            params.gravity = Gravity.END | Gravity.BOTTOM;
            params.x = Math.round(16 * d);
            params.y = Math.round(90 * d);
            params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        } else {
            params.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            params.x = Math.round(8 * d);
            params.y = 0;
            params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
    }

    private void applyMode() {
        if (button == null || wm == null) return;
        setModeParams();
        try {
            wm.updateViewLayout(button, params);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (button != null && wm != null) {
            try {
                wm.removeView(button);
            } catch (Exception ignored) {
            }
        }
        button = null;
        super.onDestroy();
    }
}

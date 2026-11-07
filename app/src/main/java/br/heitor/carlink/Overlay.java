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
import android.widget.TextView;

/**
 * Botão flutuante grande para voltar ao painel. A janela também pede orientação deitada,
 * o que faz os outros apps (Mapa, YouTube...) ficarem em paisagem enquanto espelha.
 * Precisa da permissão de sobreposição.
 */
public class Overlay extends Service {

    private WindowManager wm;
    private TextView button;

    static void start(Context c) {
        if (Settings.canDrawOverlays(c)) c.startService(new Intent(c, Overlay.class));
        else AppLog.i("Sem permissão de sobreposição: botão flutuante e paisagem automática desligados.");
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, Overlay.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (button != null || !Settings.canDrawOverlays(this)) return START_NOT_STICKY;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int size = Math.round(72 * getResources().getDisplayMetrics().density);
        button = new TextView(this);
        button.setText("🏠");
        button.setTextSize(30);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(200, 21, 101, 192));
        button.setBackground(bg);
        button.setOnClickListener(v -> DashActivity.open(this));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(size, size, type,
                // KEEP_SCREEN_ON: enquanto espelha, a tela do celular não apaga nem bloqueia,
                // em qualquer app (o espelhamento precisa da tela ligada).
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        p.x = Math.round(8 * getResources().getDisplayMetrics().density);
        p.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        try {
            wm.addView(button, p);
        } catch (Exception e) {
            AppLog.i("Não consegui criar o botão flutuante: " + e.getMessage());
            button = null;
        }
        return START_NOT_STICKY;
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

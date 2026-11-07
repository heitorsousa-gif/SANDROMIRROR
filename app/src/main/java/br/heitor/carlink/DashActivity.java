package br.heitor.carlink;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.media.projection.MediaProjectionManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Painel do carro: sempre deitado, botões enormes (a tela da central tem toque ruim),
 * aparece por cima da tela de bloqueio e mantém a tela acesa enquanto espelha.
 */
public class DashActivity extends Activity implements Link.Listener {

    static final String MAPS = "com.google.android.apps.maps";
    static final String WAZE = "com.waze";
    static final String YOUTUBE = "com.google.android.youtube";
    static final String SPOTIFY = "com.spotify.music";
    private static final int REQ_VOICE = 10;
    private static final int REQ_CAPTURE = 11;
    private static final String ACTION_USB_PERMISSION = "br.heitor.carlink.DASH_USB_PERMISSION";

    private UsbManager usb;
    private boolean askingCapture;
    private boolean askedOverlay;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Updater.ACTION_INSTALL_STATUS.equals(action)) {
                Updater.onInstallStatus(context, intent);
                return;
            }
            UsbAccessory acc = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && acc != null) connected(acc);
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                AppLog.i("Cabo desconectado.");
                if (MirrorService.running) {
                    startService(new Intent(DashActivity.this, MirrorService.class).setAction(MirrorService.ACTION_STOP));
                }
                Link.close();
            }
        }
    };

    private TextView clock;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            ui.postDelayed(this, 10_000);
        }
    };

    static void open(Context c) {
        c.startActivity(new Intent(c, DashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        AppLog.init(this);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        f.addAction(Updater.ACTION_INSTALL_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        Link.screenLong = Math.max(dm.widthPixels, dm.heightPixels);
        Link.screenShort = Math.min(dm.widthPixels, dm.heightPixels);
        AppLog.i("SandroMirror " + Updater.localBuild(this) + " | " + Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE);

        handleIntent(getIntent());
        if (!Link.isConnected()) connectIfPresent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    // ---- Conexão com a central ----

    private void handleIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) {
            UsbAccessory acc = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            AppLog.i("A central chamou o app pelo cabo USB.");
            if (acc != null) connected(acc);
        }
    }

    /** Se o cabo já está conectado (app aberto pelo ícone), conecta sem perguntar nada. */
    private void connectIfPresent() {
        UsbAccessory[] list = usb.getAccessoryList();
        if (list == null || list.length == 0) return;
        UsbAccessory acc = list[0];
        if (usb.hasPermission(acc)) {
            connected(acc);
        } else {
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(acc, PendingIntent.getBroadcast(this, 0, i, flags));
        }
    }

    private void connected(UsbAccessory acc) {
        if (Link.open(this, acc)) askCapture();
        updateStatus();
    }

    /** O Android exige confirmar a gravação de tela a cada conexão. */
    private void askCapture() {
        if (MirrorService.running || askingCapture) return;
        askingCapture = true;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void onCaptureResult(int resultCode, Intent data) {
        askingCapture = false;
        if (resultCode != RESULT_OK || data == null) {
            AppLog.i("Gravação de tela não autorizada.");
            toast("Toque no status lá em cima para espelhar");
            return;
        }
        Intent s = new Intent(this, MirrorService.class)
                .setAction(MirrorService.ACTION_START)
                .putExtra(MirrorService.EXTRA_CODE, resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
        else startService(s);
        Overlay.start(this);
        offerOverlayPermission();
        updateStatus();
    }

    /** Uma vez por abertura: oferece liberar a sobreposição (botão 🏠 e apps deitados). */
    private void offerOverlayPermission() {
        if (askedOverlay || Settings.canDrawOverlays(this)) return;
        askedOverlay = true;
        new AlertDialog.Builder(this)
                .setTitle("Botão de voltar ao painel")
                .setMessage("Libere \"Sobrepor a outros apps\" para ter o botão 🏠 flutuante e deixar o Mapa e o YouTube deitados na central.")
                .setPositiveButton("Liberar", (d, w) -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Agora não", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Settings.canDrawOverlays(this) && MirrorService.running) Overlay.start(this);
        Link.setListener(this);
        ui.post(tick);
        updateStatus();
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onStateChanged() {
        runOnUiThread(this::updateStatus);
    }

    @Override
    public void onStartRequested() {
        runOnUiThread(() -> {
            updateStatus();
            if (!MirrorService.running) askCapture();
        });
    }

    private void updateStatus() {
        switch (Link.state()) {
            case ESPELHANDO:
                status.setText("● Na central");
                status.setTextColor(Color.rgb(102, 187, 106));
                break;
            case DESCONECTADO:
                status.setText("○ Sem cabo");
                status.setTextColor(Color.rgb(239, 83, 80));
                break;
            default:
                status.setText(MirrorService.running ? "● Conectando..." : "● Toque para espelhar");
                status.setTextColor(Color.rgb(255, 202, 40));
        }
    }

    // ---- Ações ----

    private void launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast("App não instalado. Abrindo a Play Store.");
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
        }
        startUnlocked(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Fala o destino e já abre a navegação. */
    private void voiceDestination() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Para onde vamos?");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            toast("Reconhecimento de voz não disponível neste celular.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            onCaptureResult(resultCode, data);
            return;
        }
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) return;
        navigateTo(r.get(0));
    }

    private void navigateTo(String place) {
        AppLog.i("Navegar para: " + place);
        Intent i;
        if (installed(MAPS)) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).setPackage(MAPS);
        } else if (installed(WAZE)) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?navigate=yes&q=" + Uri.encode(place))).setPackage(WAZE);
        } else {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)));
        }
        startUnlocked(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Assistente de voz do celular (o mesmo que o "Ok Google"). */
    private void assistant() {
        try {
            startUnlocked(new Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast("Assistente de voz não encontrado.");
        }
    }

    /** Se o celular estiver bloqueado, pede o desbloqueio antes de abrir outro app. */
    private void startUnlocked(Intent i) {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    safeStart(i);
                }
            });
        } else {
            safeStart(i);
        }
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("Não consegui abrir.");
        }
    }

    private boolean installed(String pkg) {
        return getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---- Interface ----

    private View buildUi() {
        int gap = dp(8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 17, 23));
        root.setPadding(gap, gap, gap, gap);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        clock = text(34, Color.WHITE);
        top.addView(clock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        status = text(20, Color.WHITE);
        status.setOnClickListener(v -> {
            if (!Link.isConnected()) connectIfPresent();
            else askCapture();
        });
        top.addView(status);
        TextView gear = text(30, Color.rgb(176, 190, 197));
        gear.setText("   ⚙");
        gear.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        top.addView(gear);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row1 = row();
        row1.addView(tile("📍", "Mapa", Color.rgb(46, 125, 50), v -> launch(MAPS)), cell(gap));
        row1.addView(tile("🎤", "Falar destino", Color.rgb(21, 101, 192), v -> voiceDestination()), cell(gap));
        row1.addView(tile("🚗", "Waze", Color.rgb(0, 131, 143), v -> launch(WAZE)), cell(gap));
        root.addView(row1, rowParams());

        LinearLayout row2 = row();
        row2.addView(tile("🎵", "Spotify", Color.rgb(27, 94, 32), v -> launch(SPOTIFY)), cell(gap));
        row2.addView(tile("▶", "YouTube", Color.rgb(183, 28, 28), v -> launch(YOUTUBE)), cell(gap));
        row2.addView(tile("💬", "Assistente", Color.rgb(81, 45, 168), v -> assistant()), cell(gap));
        root.addView(row2, rowParams());
        return root;
    }

    private View tile(String icon, String label, int color, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(18));
        t.setBackground(bg);
        t.setClickable(true);
        t.setOnClickListener(l);
        TextView i = text(44, Color.WHITE);
        i.setText(icon);
        i.setGravity(Gravity.CENTER);
        TextView n = text(22, Color.WHITE);
        n.setText(label);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setGravity(Gravity.CENTER);
        t.addView(i);
        t.addView(n);
        return t;
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private static LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private static LinearLayout.LayoutParams cell(int gap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(gap, gap, gap, gap);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

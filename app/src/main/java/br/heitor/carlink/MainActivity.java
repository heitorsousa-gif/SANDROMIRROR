package br.heitor.carlink;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements AppLog.Listener, Link.Listener {

    private static final String ACTION_USB_PERMISSION = "br.heitor.carlink.USB_PERMISSION";
    private static final int REQ_CAPTURE = 1;

    private TextView status;
    private TextView hint;
    private TextView logView;
    private ScrollView logScroll;
    private UsbManager usb;
    private boolean askingCapture;

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
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && acc != null) {
                    AppLog.i("Permissão USB concedida.");
                    connected(acc);
                } else {
                    AppLog.i("Permissão USB negada.");
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                AppLog.i("Cabo desconectado.");
                stopMirror();
                Link.close();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        setContentView(buildUi());

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        f.addAction(Updater.ACTION_INSTALL_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        Link.screenLong = Math.max(dm.widthPixels, dm.heightPixels);
        Link.screenShort = Math.min(dm.widthPixels, dm.heightPixels);

        logView.setText(AppLog.text());
        AppLog.setListener(this);
        Link.setListener(this);
        AppLog.i("CarLink " + version() + " (build " + Updater.localBuild(this) + ") | " + Build.MANUFACTURER + " "
                + Build.MODEL + " | Android " + Build.VERSION.RELEASE + " | tela " + Link.screenLong + "x" + Link.screenShort);
        handleIntent(getIntent());
        updateStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        AppLog.setListener(null);
        Link.setListener(null);
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) {
            UsbAccessory acc = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            AppLog.i("A central chamou o app pelo cabo USB.");
            if (acc != null) connected(acc);
        }
    }

    private void connected(UsbAccessory acc) {
        AppLog.i("Central: " + acc.getManufacturer() + " / " + acc.getModel() + " / " + acc.getDescription());
        if (Link.open(this, acc)) askCapture();
    }

    private void connect() {
        UsbAccessory[] list = usb.getAccessoryList();
        if (list == null || list.length == 0) {
            AppLog.i("Nenhuma central encontrada. Confira o cabo (precisa ser de dados) e se o celular está desbloqueado.");
            toast("Central não encontrada");
            return;
        }
        UsbAccessory acc = list[0];
        if (usb.hasPermission(acc)) {
            connected(acc);
        } else {
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(acc, PendingIntent.getBroadcast(this, 0, i, flags));
        }
    }

    /** Pede a permissão de captura de tela (o Android exige a cada conexão). */
    private void askCapture() {
        if (MirrorService.running || askingCapture) return;
        askingCapture = true;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        askingCapture = false;
        if (resultCode != RESULT_OK || data == null) {
            AppLog.i("Captura de tela não autorizada. Toque em Espelhar para tentar de novo.");
            return;
        }
        Intent s = new Intent(this, MirrorService.class)
                .setAction(MirrorService.ACTION_START)
                .putExtra(MirrorService.EXTRA_CODE, resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
        else startService(s);
        Overlay.start(this);
        DashActivity.open(this);
        updateStatus();
    }

    private void stopMirror() {
        if (MirrorService.running) {
            startService(new Intent(this, MirrorService.class).setAction(MirrorService.ACTION_STOP));
        }
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

    @Override
    public void onLog(String line) {
        logView.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void updateStatus() {
        if (status == null) return;
        switch (Link.state()) {
            case ESPELHANDO:
                status.setText("● ESPELHANDO");
                status.setBackgroundColor(Color.rgb(27, 120, 55));
                hint.setText("Pode usar o celular normalmente. A tela aparece na central.");
                break;
            case SINCRONIZADO:
                status.setText("● CONECTADO");
                status.setBackgroundColor(Color.rgb(30, 110, 180));
                hint.setText(MirrorService.running ? "Esperando a central pedir o vídeo..." : "Toque em Espelhar e aceite a gravação de tela.");
                break;
            case CONECTADO:
                status.setText("● CABO CONECTADO");
                status.setBackgroundColor(Color.rgb(200, 140, 0));
                hint.setText("Combinando com a central...");
                break;
            default:
                status.setText("○ SEM CONEXÃO");
                status.setBackgroundColor(Color.rgb(150, 40, 40));
                hint.setText("Conecte o cabo de dados na central com o celular desbloqueado.");
        }
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("CarLink log", AppLog.text()));
        toast("Log copiado");
    }

    private void shareLog() {
        String t = AppLog.text();
        if (t.length() > 90_000) t = t.substring(t.length() - 90_000);
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
                .putExtra(Intent.EXTRA_SUBJECT, "CarLink log");
        startActivity(Intent.createChooser(i, "Mandar log"));
    }

    private void openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            toast("Sobreposição já está liberada");
            return;
        }
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private View buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(24);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(pad, pad * 2, pad, pad * 2);
        root.addView(status, matchWrap());

        hint = new TextView(this);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, pad, 0, pad);
        root.addView(hint, matchWrap());

        Button mirror = button("▶  ESPELHAR", v -> {
            if (!Link.isConnected()) connect();
            else askCapture();
        });
        mirror.setTextSize(20);
        root.addView(mirror, matchWrap());

        LinearLayout row1 = row();
        row1.addView(button("Parar", v -> stopMirror()), weight());
        row1.addView(button("⟳ Atualizar app", v -> Updater.check(this)), weight());
        root.addView(row1, matchWrap());

        LinearLayout row2 = row();
        row2.addView(button("Mandar log", v -> shareLog()), weight());
        row2.addView(button("Copiar log", v -> copyLog()), weight());
        row2.addView(button("Sobreposição", v -> openOverlaySettings()), weight());
        root.addView(row2, matchWrap());

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

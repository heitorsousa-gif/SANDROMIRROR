package br.heitor.carlink;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Modo direção: tela deitada para a central, com relógio, a música tocando (botões grandes)
 * e a próxima instrução do Maps. A ideia é mexer só pela tela da central, sem pegar no celular.
 */
public class DriveActivity extends Activity implements Avisos.Listener {

    private static final int REQ_BG = 30;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextView clock, date;
    private LinearLayout navCard, musicCard, permCard;
    private ImageView navIcon, art;
    private TextView navDist, navText, title, artist, playBtn;
    private MediaSessionManager msm;
    private MediaController controller;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
            date.setText(new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "BR")).format(now));
            ui.postDelayed(this, 5_000);
        }
    };

    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateMusic();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateMusic();
        }

        @Override
        public void onSessionDestroyed() {
            pickController();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsCb = list -> pickController();

    static void open(Context c) {
        c.startActivity(new Intent(c, DriveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        setContentView(buildUi());
        applyBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
        Avisos.setListener(this);
        boolean ok = Avisos.enabled(this);
        permCard.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (ok) {
            try {
                msm.addOnActiveSessionsChangedListener(sessionsCb, Avisos.component(this), ui);
            } catch (SecurityException ignored) {
            }
            pickController();
        } else {
            musicCard.setVisibility(View.GONE);
        }
        onNavChanged();
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        Avisos.setListener(null);
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsCb);
        } catch (Exception ignored) {
        }
        setController(null);
        super.onPause();
    }

    // ---- Música ----

    /** Escolhe a sessão de mídia mais relevante: a que está tocando, ou a mais recente. */
    private void pickController() {
        MediaController best = null;
        try {
            List<MediaController> list = msm.getActiveSessions(Avisos.component(this));
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
        } catch (SecurityException ignored) {
        }
        setController(best);
    }

    private void setController(MediaController c) {
        if (controller != null) controller.unregisterCallback(mediaCb);
        controller = c;
        if (controller != null) controller.registerCallback(mediaCb, ui);
        updateMusic();
    }

    private void updateMusic() {
        if (controller == null || controller.getMetadata() == null) {
            musicCard.setVisibility(Avisos.enabled(this) ? View.VISIBLE : View.GONE);
            title.setText("Nada tocando");
            artist.setText("Toque em 🎵 para abrir o Spotify");
            art.setImageDrawable(null);
            playBtn.setText("▶");
            return;
        }
        musicCard.setVisibility(View.VISIBLE);
        MediaMetadata md = controller.getMetadata();
        CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence a = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        title.setText(t == null ? "" : t);
        artist.setText(a == null ? "" : a);
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        art.setImageBitmap(bmp);
        PlaybackState st = controller.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        playBtn.setText(playing ? "⏸" : "▶");
    }

    private void playPause() {
        if (controller == null) {
            openApp(DashActivity.SPOTIFY);
            return;
        }
        PlaybackState st = controller.getPlaybackState();
        if (st != null && st.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    // ---- Navegação ----

    @Override
    public void onNavChanged() {
        if (Avisos.navTitle == null && Avisos.navText == null) {
            navCard.setVisibility(View.GONE);
            return;
        }
        navCard.setVisibility(View.VISIBLE);
        navDist.setText(Avisos.navTitle == null ? "" : Avisos.navTitle);
        navText.setText(Avisos.navText == null ? "" : Avisos.navText);
        navIcon.setImageDrawable(Avisos.navIcon);
        navIcon.setVisibility(Avisos.navIcon == null ? View.GONE : View.VISIBLE);
    }

    // ---- Fundo ----

    private void chooseBackground() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try {
            startActivityForResult(i, REQ_BG);
        } catch (ActivityNotFoundException e) {
            toast("Não achei a galeria.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_BG || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        prefs().edit().putString("fundo", uri.toString()).apply();
        applyBackground();
    }

    private void applyBackground() {
        Drawable bg = null;
        String s = prefs().getString("fundo", null);
        if (s != null) {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(s))) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                Bitmap b = BitmapFactory.decodeStream(in, null, o);
                if (b != null) bg = new BitmapDrawable(getResources(), b);
            } catch (Exception ignored) {
            }
        }
        if (bg == null) {
            bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(13, 27, 42), Color.rgb(27, 38, 59), Color.rgb(65, 90, 119)});
        }
        root.setBackground(bg);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("sandro", MODE_PRIVATE);
    }

    // ---- Ações ----

    private void openApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast("App não instalado.");
            return;
        }
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- Interface ----

    private View buildUi() {
        int g = dp(10);
        root = new FrameLayout(this);

        // Véu escuro por cima do fundo, para o texto ficar legível em qualquer foto.
        View veil = new View(this);
        veil.setBackgroundColor(Color.argb(110, 0, 0, 0));
        root.addView(veil, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setPadding(g * 2, g * 2, g * 2, g * 2);
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Coluna da esquerda: relógio e botões.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        clock = text(72, Color.WHITE, true);
        date = text(18, Color.argb(220, 255, 255, 255), false);
        left.addView(clock);
        left.addView(date);
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, g * 2, 0, 0);
        btns.addView(pill("🏠 Painel", v -> DashActivity.open(this)), pillParams(g));
        btns.addView(pill("🗺 Mapa", v -> openApp(DashActivity.MAPS)), pillParams(g));
        left.addView(btns);
        TextView bgBtn = text(14, Color.argb(200, 255, 255, 255), false);
        bgBtn.setText("🖼 Trocar fundo");
        bgBtn.setPadding(0, g, 0, 0);
        bgBtn.setOnClickListener(v -> chooseBackground());
        left.addView(bgBtn);
        main.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // Coluna da direita: navegação, música e aviso de permissão.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);

        navCard = card(Color.argb(220, 46, 125, 50));
        navCard.setOrientation(LinearLayout.HORIZONTAL);
        navCard.setGravity(Gravity.CENTER_VERTICAL);
        navIcon = new ImageView(this);
        navCard.addView(navIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout navTexts = new LinearLayout(this);
        navTexts.setOrientation(LinearLayout.VERTICAL);
        navTexts.setPadding(g, 0, 0, 0);
        navDist = text(30, Color.WHITE, true);
        navText = text(16, Color.WHITE, false);
        navText.setMaxLines(2);
        navTexts.addView(navDist);
        navTexts.addView(navText);
        navCard.addView(navTexts);
        navCard.setOnClickListener(v -> openApp(DashActivity.MAPS));
        right.addView(navCard, cardParams(g));

        musicCard = card(Color.argb(200, 20, 20, 20));
        LinearLayout musicTop = new LinearLayout(this);
        musicTop.setGravity(Gravity.CENTER_VERTICAL);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(Color.argb(80, 255, 255, 255));
        musicTop.addView(art, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout musicTexts = new LinearLayout(this);
        musicTexts.setOrientation(LinearLayout.VERTICAL);
        musicTexts.setPadding(g, 0, 0, 0);
        title = text(20, Color.WHITE, true);
        title.setSingleLine(true);
        artist = text(15, Color.argb(210, 255, 255, 255), false);
        artist.setSingleLine(true);
        musicTexts.addView(title);
        musicTexts.addView(artist);
        musicTop.addView(musicTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        musicTop.setOnClickListener(v -> openApp(controller != null ? controller.getPackageName() : DashActivity.SPOTIFY));
        musicCard.addView(musicTop);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, g, 0, 0);
        controls.addView(control("⏮", v -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        }), controlParams(g));
        playBtn = control("▶", v -> playPause());
        controls.addView(playBtn, controlParams(g));
        controls.addView(control("⏭", v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        }), controlParams(g));
        musicCard.addView(controls);
        right.addView(musicCard, cardParams(g));

        permCard = card(Color.argb(220, 230, 81, 0));
        TextView perm = text(16, Color.WHITE, true);
        perm.setText("Toque aqui e libere o SandroMirror em \"Acesso a notificações\" para ver a música e a rota do Maps.");
        permCard.addView(perm);
        permCard.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (ActivityNotFoundException e) {
                toast("Abra Configurações > Notificações > Acesso a notificações.");
            }
        });
        right.addView(permCard, cardParams(g));

        main.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        return root;
    }

    private LinearLayout card(int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(20));
        c.setBackground(bg);
        return c;
    }

    private TextView pill(String label, View.OnClickListener l) {
        TextView t = text(18, Color.WHITE, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        int p = dp(12);
        t.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(150, 255, 255, 255));
        bg.setCornerRadius(dp(30));
        t.setBackground(bg);
        t.setOnClickListener(l);
        return t;
    }

    private TextView control(String label, View.OnClickListener l) {
        TextView t = text(34, Color.WHITE, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(70, 255, 255, 255));
        t.setBackground(bg);
        t.setOnClickListener(l);
        return t;
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setShadowLayer(4, 0, 1, Color.argb(160, 0, 0, 0));
        return t;
    }

    private LinearLayout.LayoutParams cardParams(int g) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, g / 2, 0, g / 2);
        return p;
    }

    private LinearLayout.LayoutParams pillParams(int g) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, g, 0);
        return p;
    }

    private LinearLayout.LayoutParams controlParams(int g) {
        int sz = dp(72);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(sz, sz);
        p.setMargins(g, 0, g, 0);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

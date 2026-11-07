package br.heitor.carlink;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Modo direção: as fotos do usuário passando como papel de parede, a música tocando e a
 * próxima instrução do Maps, no visual Windows 95 cyberpunk.
 * Deitado (modo central): para usar pela tela da central. Em pé (modo celular): para o
 * copiloto, com os ajustes das fotos.
 */
public class DriveActivity extends Activity implements Avisos.Listener, Slideshow.Listener {

    private static final int REQ_FOTOS = 31;
    private static final int REQ_PASTA = 32;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController controller;
    private boolean resumed;

    // Elementos da tela (recriados quando gira).
    private Slideshow slides;
    private View navWindow, musicWindow, permWindow, emptyWindow;
    private ImageView navIcon, art, playBtn;
    private TextView navDist, navText, title, artist, pauseBtn, pinBtn, countText;
    private Visualizer viz;
    private final TextView[] intervalBtns = new TextView[4];
    private static final int[] INTERVALS = {8, 15, 30, 60};

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
        Prefs.applyOrientation(this);
        msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        rebuild();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebuild();
        if (resumed) onResumeBindings();
    }

    private void rebuild() {
        if (slides != null) slides.stop();
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        setContentView(portrait ? buildPortrait() : buildLandscape());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Prefs.applyBrightness(this);
        onResumeBindings();
    }

    private void onResumeBindings() {
        slides.start();
        Avisos.setListener(this);
        boolean ok = Avisos.enabled(this);
        permWindow.setVisibility(ok ? View.GONE : View.VISIBLE);
        musicWindow.setVisibility(ok ? View.VISIBLE : View.GONE);
        if (ok) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsCb);
                msm.addOnActiveSessionsChangedListener(sessionsCb, Avisos.component(this), ui);
            } catch (SecurityException ignored) {
            }
            pickController();
        }
        onNavChanged();
        onSlideStateChanged();
    }

    @Override
    protected void onPause() {
        resumed = false;
        slides.stop();
        Avisos.setListener(null);
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsCb);
        } catch (Exception ignored) {
        }
        setController(null);
        super.onPause();
    }

    // ---- Música ----

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
        if (title == null) return;
        MediaMetadata md = controller == null ? null : controller.getMetadata();
        if (md == null) {
            title.setText("NADA TOCANDO");
            artist.setText("toque no play para abrir o Spotify");
            art.setImageDrawable(null);
            playBtn.setImageResource(R.drawable.ic_play);
            viz.setPlaying(false);
            return;
        }
        CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence a = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        title.setText(t == null ? "" : t.toString().toUpperCase());
        artist.setText(a == null ? "" : a);
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        art.setImageBitmap(bmp);
        PlaybackState st = controller.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        viz.setPlaying(playing);
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
        if (navWindow == null) return;
        if (Avisos.navTitle == null && Avisos.navText == null) {
            navWindow.setVisibility(View.GONE);
            return;
        }
        navWindow.setVisibility(View.VISIBLE);
        navDist.setText(Avisos.navTitle == null ? "" : Avisos.navTitle);
        navText.setText(Avisos.navText == null ? "" : Avisos.navText);
        navIcon.setImageDrawable(Avisos.navIcon);
        navIcon.setVisibility(Avisos.navIcon == null ? View.GONE : View.VISIBLE);
    }

    // ---- Fotos ----

    @Override
    public void onSlideStateChanged() {
        if (slides == null) return;
        boolean has = slides.count() > 0 || slides.isPinned();
        if (emptyWindow != null) emptyWindow.setVisibility(has ? View.GONE : View.VISIBLE);
        if (pauseBtn != null) pauseBtn.setText(slides.isPaused() ? "CONTINUAR" : "PAUSAR");
        if (pinBtn != null) {
            pinBtn.setText(slides.isPinned() ? "SOLTAR" : "FIXAR");
            pinBtn.setTextColor(slides.isPinned() ? Retro.YELLOW : Color.WHITE);
        }
        if (countText != null) {
            int n = Prefs.fontesFotos(this).size();
            countText.setText(slides.count() + " fotos na rotação (" + n + (n == 1 ? " origem)" : " origens)"));
        }
        int cur = Prefs.intervalo(this);
        for (int i = 0; i < intervalBtns.length; i++) {
            if (intervalBtns[i] != null) intervalBtns[i].setTextColor(INTERVALS[i] == cur ? Retro.YELLOW : Color.WHITE);
        }
    }

    private void pickPhotos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_FOTOS);
        } catch (ActivityNotFoundException e) {
            toast("Não achei a galeria.");
        }
    }

    private void pickFolder() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PASTA);
        } catch (ActivityNotFoundException e) {
            toast("Este celular não deixa escolher pastas. Use Adicionar fotos.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (requestCode == REQ_FOTOS) {
            int added = 0;
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int k = 0; k < clip.getItemCount(); k++) {
                    if (addPhoto(clip.getItemAt(k).getUri(), flags)) added++;
                }
            } else if (data.getData() != null && addPhoto(data.getData(), flags)) {
                added++;
            }
            toast(added + (added == 1 ? " foto adicionada" : " fotos adicionadas"));
        } else if (requestCode == REQ_PASTA && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, flags);
            } catch (Exception ignored) {
            }
            Prefs.addFonteFoto(this, "d:" + tree);
            toast("Pasta adicionada. Fotos novas que você puser nela entram sozinhas.");
        }
        slides.start();
    }

    private boolean addPhoto(Uri u, int flags) {
        if (u == null) return false;
        try {
            getContentResolver().takePersistableUriPermission(u, flags);
        } catch (Exception ignored) {
        }
        Prefs.addFonteFoto(this, "f:" + u);
        return true;
    }

    // ---- Ações ----

    private void toggleMode() {
        boolean cel = !Prefs.modoCelular(this);
        Prefs.setModoCelular(this, cel);
        Prefs.applyOrientation(this);
        Prefs.applyBrightness(this);
        Overlay.refresh(this);
        toast(cel ? "Modo celular: tela em pé para o copiloto" : "Modo central: tela deitada para a central");
    }

    private void openApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast("App não instalado.");
            return;
        }
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("Abra Configurações > Notificações > Acesso a notificações.");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- Peças da interface ----

    private int dp(float v) {
        return Retro.dp(this, v);
    }

    private Slideshow newSlides() {
        slides = new Slideshow(this);
        slides.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(20, 0, 40), Color.rgb(60, 10, 90), Color.rgb(0, 60, 90)}));
        slides.setListener(this);
        return slides;
    }

    /** Janela do player: capa, música, equalizador e botões. */
    private View buildMusicWindow(boolean compact) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int b = dp(3);
        art.setPadding(b, b, b, b);
        art.setBackground(new Retro.Bevel(Color.BLACK, Retro.DARK, Retro.PINK, b));
        art.setClickable(true);
        art.setOnClickListener(v -> openApp(controller != null ? controller.getPackageName() : DashActivity.SPOTIFY));
        int artSize = dp(compact ? 84 : 96);
        row.addView(art, new LinearLayout.LayoutParams(artSize, artSize));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        title = Retro.text(this, "", compact ? 17 : 19, Color.WHITE, Retro.future(this), Retro.CYAN);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setSelected(true);
        artist = Retro.text(this, "", compact ? 20 : 22, Retro.PINK, Retro.term(this), Retro.PINK);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        artist.setPadding(0, dp(6), 0, dp(6));
        viz = new Visualizer(this);
        texts.addView(title);
        texts.addView(artist);
        texts.addView(viz, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(compact ? 30 : 36)));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(row);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(10), 0, 0);
        int sz = dp(compact ? 60 : 66);
        controls.addView(Retro.iconButton(this, R.drawable.ic_prev, Retro.CYAN, v -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        }), square(sz));
        playBtn = Retro.iconButton(this, R.drawable.ic_play, Retro.YELLOW, v -> playPause());
        controls.addView(playBtn, square(sz));
        controls.addView(Retro.iconButton(this, R.drawable.ic_next, Retro.CYAN, v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        }), square(sz));
        body.addView(controls);
        return Retro.window(this, "SOM.EXE", body);
    }

    /** Janela da navegação do Maps (só aparece com rota ativa). */
    private View buildNavWindow() {
        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER_VERTICAL);
        navIcon = new ImageView(this);
        navIcon.setColorFilter(Retro.CYAN);
        body.addView(navIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), 0, 0, 0);
        navDist = Retro.text(this, "", 24, Retro.YELLOW, Retro.future(this), Retro.YELLOW);
        navText = Retro.text(this, "", 22, Color.WHITE, Retro.term(this), Retro.CYAN);
        navText.setMaxLines(2);
        t.addView(navDist);
        t.addView(navText);
        body.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View w = Retro.window(this, "ROTA.EXE", body);
        w.setClickable(true);
        w.setOnClickListener(v -> openApp(DashActivity.MAPS));
        navWindow = w;
        return w;
    }

    private View buildPermWindow() {
        TextView t = Retro.text(this, "Toque aqui e libere o SandroMirror em \"Acesso a notificações\". "
                + "Assim aparecem a música e a rota do Maps.", 22, Color.WHITE, Retro.term(this), Retro.PINK);
        View w = Retro.window(this, "AVISO.EXE", t);
        w.setClickable(true);
        w.setOnClickListener(v -> openNotificationAccess());
        permWindow = w;
        return w;
    }

    private View buildEmptyWindow(String msg) {
        TextView t = Retro.text(this, msg, 22, Color.WHITE, Retro.term(this), Retro.CYAN);
        emptyWindow = Retro.window(this, "FOTOS.EXE", t);
        return emptyWindow;
    }

    /** Barra de tarefas estilo Windows 95. */
    private LinearLayout taskbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(4);
        bar.setPadding(p, p, p, p);
        bar.setBackground(new Retro.Bevel(Color.argb(235, 30, 8, 60), Retro.LIGHT, Retro.DARK, dp(2)));
        return bar;
    }

    private LinearLayout.LayoutParams barItem() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private LinearLayout.LayoutParams square(int sz) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.setMargins(dp(8), 0, dp(8), 0);
        return lp;
    }

    private View photoControls(LinearLayout into) {
        into.addView(Retro.button(this, "<", Retro.FACE, v -> slides.prev()), barItem());
        pauseBtn = Retro.button(this, "PAUSAR", Retro.FACE, v -> slides.togglePause());
        into.addView(pauseBtn, barItem());
        pinBtn = Retro.button(this, "FIXAR", Retro.FACE, v -> slides.togglePin());
        into.addView(pinBtn, barItem());
        into.addView(Retro.button(this, ">", Retro.FACE, v -> slides.next()), barItem());
        return into;
    }

    // ---- Deitado: modo central ----

    private View buildLandscape() {
        FrameLayout root = new FrameLayout(this);
        root.addView(newSlides(), match());
        View scan = new View(this);
        scan.setBackground(new Retro.Scanlines(this));
        root.addView(scan, match());

        int g = dp(12);
        // Faixa da esquerda (100dp) fica livre para o botão azul flutuante.
        int leftFree = dp(100);
        int barH = dp(58);

        FrameLayout content = new FrameLayout(this);
        content.setPadding(leftFree, g, g, barH + g);
        root.addView(content, match());

        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        View nav = buildNavWindow();
        content.addView(nav, navLp);

        View music = buildMusicWindow(false);
        musicWindow = music;
        content.addView(music, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));
        View perm = buildPermWindow();
        content.addView(perm, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));
        View empty = buildEmptyWindow("Nenhuma foto ainda. Toque em MODO CELULAR para escolher as suas.");
        content.addView(empty, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        // As janelas ocupam pouco mais da metade da largura útil, para as fotos aparecerem.
        content.post(() -> {
            int usable = content.getWidth() - content.getPaddingLeft() - content.getPaddingRight();
            setWidth(nav, usable * 52 / 100);
            setWidth(music, usable * 56 / 100);
            setWidth(perm, usable * 56 / 100);
            setWidth(empty, usable * 40 / 100);
        });

        LinearLayout bar = taskbar();
        bar.addView(Retro.button(this, "PAINEL", Retro.PURPLE, v -> DashActivity.open(this)), barItem());
        bar.addView(Retro.button(this, "MAPA", Retro.PURPLE, v -> openApp(DashActivity.MAPS)), barItem());
        View sep = new View(this);
        bar.addView(sep, new LinearLayout.LayoutParams(dp(14), 1));
        photoControls(bar);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(Retro.button(this, "MODO CELULAR", Retro.darker(Retro.PINK), v -> toggleMode()), barItem());
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM));
        return root;
    }

    // ---- Em pé: modo celular (copiloto) ----

    private View buildPortrait() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(20, 0, 40), Color.rgb(45, 8, 75), Color.rgb(0, 40, 70)}));
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int g = dp(12);
        col.setPadding(g, g, g, dp(110));
        scroll.addView(col);
        root.addView(scroll, match());
        View scan = new View(this);
        scan.setBackground(new Retro.Scanlines(this));
        root.addView(scan, match());

        // Fotos: prévia com os controles embaixo.
        LinearLayout photoBody = new LinearLayout(this);
        photoBody.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(newSlides(), match());
        View empty = buildEmptyWindow("Nenhuma foto ainda. Adicione abaixo.");
        frame.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        photoBody.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        LinearLayout pc = new LinearLayout(this);
        pc.setGravity(Gravity.CENTER);
        pc.setPadding(0, dp(8), 0, 0);
        photoControls(pc);
        photoBody.addView(pc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        col.addView(Retro.window(this, "FOTOS.EXE", photoBody), block(g));

        musicWindow = buildMusicWindow(true);
        col.addView(musicWindow, block(g));
        col.addView(buildPermWindow(), block(g));
        col.addView(buildNavWindow(), block(g));

        // Ajustes.
        LinearLayout set = new LinearLayout(this);
        set.setOrientation(LinearLayout.VERTICAL);
        countText = Retro.text(this, "", 22, Color.WHITE, Retro.term(this), Retro.CYAN);
        set.addView(countText);
        LinearLayout addRow = new LinearLayout(this);
        addRow.setPadding(0, dp(8), 0, 0);
        addRow.addView(Retro.button(this, "ADICIONAR FOTOS", Retro.PURPLE, v -> pickPhotos()), weight());
        addRow.addView(Retro.button(this, "ADICIONAR PASTA", Retro.PURPLE, v -> pickFolder()), weight());
        set.addView(addRow);
        TextView tip = Retro.text(this, "Dica: com uma pasta, as fotos novas que você salvar nela entram sozinhas.",
                18, Color.argb(200, 255, 255, 255), Retro.term(this), 0);
        tip.setPadding(0, dp(6), 0, 0);
        set.addView(tip);

        TextView ivl = Retro.text(this, "TROCAR FOTO A CADA:", 22, Retro.PINK, Retro.term(this), Retro.PINK);
        ivl.setPadding(0, dp(14), 0, dp(6));
        set.addView(ivl);
        LinearLayout ivRow = new LinearLayout(this);
        for (int i = 0; i < INTERVALS.length; i++) {
            final int s = INTERVALS[i];
            intervalBtns[i] = Retro.button(this, s + "s", Retro.FACE, v -> {
                Prefs.setIntervalo(this, s);
                onSlideStateChanged();
            });
            ivRow.addView(intervalBtns[i], weight());
        }
        set.addView(ivRow);

        LinearLayout endRow = new LinearLayout(this);
        endRow.setPadding(0, dp(14), 0, 0);
        endRow.addView(Retro.button(this, "LIMPAR FOTOS", Retro.darker(Retro.PINK), v -> {
            Prefs.limparFotos(this);
            slides.start();
            toast("Fotos removidas da rotação (continuam na sua galeria).");
        }), weight());
        set.addView(endRow);
        col.addView(Retro.window(this, "AJUSTES.EXE", set), block(g));

        LinearLayout navRow = new LinearLayout(this);
        navRow.addView(Retro.button(this, "PAINEL", Retro.PURPLE, v -> DashActivity.open(this)), weight());
        navRow.addView(Retro.button(this, "MODO CENTRAL", Retro.darker(Retro.PINK), v -> toggleMode()), weight());
        col.addView(navRow, block(g));
        return root;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams block(int g) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, g);
        return lp;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private static void setWidth(View v, int w) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = w;
        v.setLayoutParams(lp);
    }
}

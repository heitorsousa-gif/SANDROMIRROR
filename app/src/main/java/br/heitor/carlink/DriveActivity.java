package br.heitor.carlink;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
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
 * Modo direção no visual do Windows antigo: as fotos do usuário passando na tela toda
 * (fundo = a própria foto desfocada; nada cobre a foto nítida), o player da música tocando e a próxima instrução do Maps.
 * Deitado (modo central): para usar pela tela da central. Em pé (modo celular): para o
 * copiloto. As fotos e o tema são escolhidos nos Ajustes.
 */
public class DriveActivity extends Activity implements Avisos.Listener, Slideshow.Listener {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController controller;
    private boolean resumed;
    private int builtTheme = -1;

    // Elementos da tela (recriados quando gira ou troca o tema).
    private Slideshow slides;
    private View navWindow, musicWindow, permWindow, emptyText;
    private ImageView navIcon, art, playBtn;
    private TextView navDist, navText, title, artist, pauseBtn, pinBtn, apagarBtn;
    private Visualizer viz;

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
        AppLog.init(this);
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
        Retro.applyTheme(this);
        builtTheme = Prefs.tema(this);
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        setContentView(portrait ? buildPortrait() : buildLandscape());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Voltando dos Ajustes com outro tema: redesenha.
        if (Prefs.tema(this) != builtTheme) rebuild();
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
        updateApagar();
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
        if (emptyText != null) emptyText.setVisibility(has ? View.GONE : View.VISIBLE);
        if (pauseBtn != null) pauseBtn.setText(slides.isPaused() ? "CONTINUAR" : "PAUSAR");
        if (pinBtn != null) {
            pinBtn.setText(slides.isPinned() ? "SOLTAR" : "FIXAR");
            pinBtn.setTextColor(slides.isPinned() ? Retro.ACTIVE : Color.BLACK);
        }
    }

    // ---- Ações ----

    private void toggleApagar() {
        Prefs.setApagarTela(this, !Prefs.apagarTela(this));
        Prefs.applyBrightness(this);
        updateApagar();
    }

    private void updateApagar() {
        if (apagarBtn == null) return;
        boolean on = Prefs.apagarTela(this);
        apagarBtn.setText(on ? "APAGAR TELA: ON" : "APAGAR TELA: OFF");
        apagarBtn.setTextColor(on ? Retro.ACTIVE : Color.BLACK);
    }

    private void toggleMode() {
        boolean cel = !Prefs.modoCelular(this);
        Prefs.setModoCelular(this, cel);
        Prefs.applyOrientation(this);
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

    /**
     * Fotos ocupando a tela inteira: o fundo é a própria foto desfocada e a foto nítida
     * aparece inteira na área que sobra (definida depois com setPhotoInsets).
     */
    private Slideshow buildSlides() {
        slides = new Slideshow(this);
        slides.setBackgroundColor(Retro.DESKTOP);
        slides.setListener(this);
        return slides;
    }

    private TextView buildEmptyText() {
        TextView empty = Retro.text(this, "Nenhuma foto ainda.\nAdicione em AJUSTES, no painel.",
                24, Color.WHITE, Retro.term(this), Color.BLACK);
        empty.setGravity(Gravity.CENTER);
        emptyText = empty;
        return empty;
    }

    /** Janela do player: capa, música, equalizador e botões. */
    private View buildMusicWindow(boolean compact) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int b = dp(2);
        art.setPadding(b, b, b, b);
        art.setBackground(new Retro.Bevel(Color.BLACK, Retro.SHADOW, Retro.LIGHT, b));
        art.setClickable(true);
        art.setOnClickListener(v -> openApp(controller != null ? controller.getPackageName() : DashActivity.SPOTIFY));
        int artSize = dp(compact ? 84 : 92);
        row.addView(art, new LinearLayout.LayoutParams(artSize, artSize));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        title = Retro.text(this, "", compact ? 17 : 18, Color.WHITE, Retro.future(this), 0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setSelected(true);
        artist = Retro.text(this, "", compact ? 20 : 22, Retro.GREEN, Retro.term(this), 0);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        artist.setPadding(0, dp(6), 0, dp(6));
        viz = new Visualizer(this);
        texts.addView(title);
        texts.addView(artist);
        texts.addView(viz, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(compact ? 28 : 32)));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(row);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(10), 0, 0);
        int sz = dp(compact ? 64 : 76);
        controls.addView(Retro.iconButton(this, R.drawable.ic_prev, Color.BLACK, v -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        }), square(sz));
        playBtn = Retro.iconButton(this, R.drawable.ic_play, Color.rgb(0, 110, 0), v -> playPause());
        controls.addView(playBtn, square(sz));
        controls.addView(Retro.iconButton(this, R.drawable.ic_next, Color.BLACK, v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        }), square(sz));
        body.addView(controls);
        musicWindow = Retro.window(this, "SOM.EXE", body);
        return musicWindow;
    }

    /** Janela da navegação do Maps (só aparece com rota ativa). */
    private View buildNavWindow() {
        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER_VERTICAL);
        navIcon = new ImageView(this);
        navIcon.setColorFilter(Retro.CYAN);
        body.addView(navIcon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), 0, 0, 0);
        navDist = Retro.text(this, "", 22, Retro.YELLOW, Retro.future(this), 0);
        navText = Retro.text(this, "", 21, Retro.CYAN, Retro.term(this), 0);
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
                + "Assim aparecem a música e a rota do Maps.", 21, Retro.GREEN, Retro.term(this), 0);
        View w = Retro.window(this, "AVISO.EXE", t);
        w.setClickable(true);
        w.setOnClickListener(v -> openNotificationAccess());
        permWindow = w;
        return w;
    }

    /** Barra de tarefas estilo Windows. */
    private LinearLayout taskbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(4);
        bar.setPadding(p, p, p, p);
        bar.setBackground(new Retro.Bevel(Retro.SILVER, Retro.LIGHT, Retro.SHADOW, dp(2)));
        return bar;
    }

    private LinearLayout.LayoutParams barItem() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private LinearLayout.LayoutParams square(int sz) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.setMargins(dp(8), 0, dp(8), 0);
        return lp;
    }

    private void photoControls(LinearLayout into, LinearLayout.LayoutParams lp) {
        into.addView(Retro.button(this, "<", Retro.SILVER, v -> slides.prev()), copy(lp));
        pauseBtn = Retro.button(this, "PAUSAR", Retro.SILVER, v -> slides.togglePause());
        into.addView(pauseBtn, copy(lp));
        pinBtn = Retro.button(this, "FIXAR", Retro.SILVER, v -> slides.togglePin());
        into.addView(pinBtn, copy(lp));
        into.addView(Retro.button(this, ">", Retro.SILVER, v -> slides.next()), copy(lp));
    }

    private static LinearLayout.LayoutParams copy(LinearLayout.LayoutParams lp) {
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(lp.width, lp.height, lp.weight);
        c.setMargins(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin);
        return c;
    }

    // ---- Deitado: modo central ----

    private View buildLandscape() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Retro.DESKTOP);
        int g = dp(10);
        // Faixa da esquerda (100dp) é do botão azul flutuante: ali só aparece o fundo desfocado.
        int leftFree = dp(100);
        int barH = dp(52);

        root.addView(buildSlides(), match());
        TextView empty = buildEmptyText();
        root.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        // Coluna da direita: player, rota e aviso de permissão.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(buildMusicWindow(false), block(g));
        right.addView(buildNavWindow(), block(g));
        right.addView(buildPermWindow(), block(g));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        rlp.setMargins(0, g, g, barH + g);
        root.addView(right, rlp);

        // A foto nítida fica com todo o resto: da faixa do botão até a coluna do player.
        // Recalcula sempre que o tamanho da tela muda (ao girar, por exemplo).
        onSizeChanged(root, (w, h) -> {
            // Coluna do player mais larga, para os botões de música ficarem fáceis de acertar.
            int colW = Math.min(dp(440), Math.max(dp(360), w * 40 / 100));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) right.getLayoutParams();
            lp.width = colW;
            right.setLayoutParams(lp);
            slides.setPhotoInsets(w, h, leftFree, g, colW + g * 2, barH + g, true);
            FrameLayout.LayoutParams el = (FrameLayout.LayoutParams) empty.getLayoutParams();
            el.setMargins(leftFree, 0, colW + g * 2, barH);
            empty.setLayoutParams(el);
        });

        LinearLayout bar = taskbar();
        bar.addView(Retro.button(this, "PAINEL", Retro.SILVER, v -> DashActivity.open(this)), barItem());
        bar.addView(Retro.button(this, "MAPA", Retro.SILVER, v -> openApp(DashActivity.MAPS)), barItem());
        bar.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        photoControls(bar, barItem());
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        apagarBtn = Retro.button(this, "APAGAR TELA: OFF", Retro.SILVER, v -> toggleApagar());
        bar.addView(apagarBtn, barItem());
        bar.addView(Retro.button(this, "MODO CELULAR", Retro.SILVER, v -> toggleMode()), barItem());
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM));
        return root;
    }

    // ---- Em pé: modo celular (copiloto) ----

    private View buildPortrait() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Retro.DESKTOP);
        int g = dp(10);
        int photoH = dp(380);

        // Fundo da tela inteira = foto desfocada; a foto nítida fica no alto.
        root.addView(buildSlides(), match());
        TextView empty = buildEmptyText();
        root.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, photoH, Gravity.TOP));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(g, photoH + g, g, dp(110));
        scroll.addView(col);
        root.addView(scroll, match());
        onSizeChanged(root, (w, h) -> slides.setPhotoInsets(w, h, g, g, g, Math.max(0, h - photoH), false));

        LinearLayout pc = new LinearLayout(this);
        pc.setGravity(Gravity.CENTER);
        pc.setPadding(0, 0, 0, g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        photoControls(pc, lp);
        flushEdges(pc);
        col.addView(pc);

        col.addView(buildMusicWindow(true), block(g));
        col.addView(buildNavWindow(), block(g));
        col.addView(buildPermWindow(), block(g));

        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, dp(52), 1f);
        wl.setMargins(dp(3), 0, dp(3), 0);
        row.addView(Retro.button(this, "PAINEL", Retro.SILVER, v -> DashActivity.open(this)), copy(wl));
        row.addView(Retro.button(this, "MODO CENTRAL", Retro.SILVER, v -> toggleMode()), copy(wl));
        flushEdges(row);
        col.addView(row, block(g));
        apagarBtn = Retro.button(this, "APAGAR TELA: OFF", Retro.SILVER, v -> toggleApagar());
        col.addView(apagarBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return root;
    }

    /** Tira o recuo do primeiro e do último botão, para a fileira alinhar com as janelas. */
    private static void flushEdges(LinearLayout row) {
        int n = row.getChildCount();
        if (n == 0) return;
        LinearLayout.LayoutParams first = (LinearLayout.LayoutParams) row.getChildAt(0).getLayoutParams();
        first.leftMargin = 0;
        LinearLayout.LayoutParams last = (LinearLayout.LayoutParams) row.getChildAt(n - 1).getLayoutParams();
        last.rightMargin = 0;
    }

    interface SizeAction {
        void run(int w, int h);
    }

    /** Executa a ação com o tamanho real da tela, de novo a cada mudança de tamanho. */
    private static void onSizeChanged(View root, SizeAction action) {
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t;
            if (w > 0 && h > 0 && (w != or - ol || h != ob - ot)) v.post(() -> action.run(w, h));
        });
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams block(int g) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, g);
        return lp;
    }
}

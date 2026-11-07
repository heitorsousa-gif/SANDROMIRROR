package br.heitor.carlink;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Papel de parede com as fotos do usuário passando, sem nada por cima da foto.
 * Modo "preencher" (padrão): a foto ocupa toda a área com zoom, centralizada um pouco
 * acima do meio (onde costumam ficar rosto e orelhas). Modo "inteira": a foto aparece
 * completa e o resto é preenchido pela própria foto desfocada, que também é o fundo da tela.
 * Pode pausar (até sair da tela) ou fixar uma foto (fica guardado).
 */
class Slideshow extends FrameLayout {

    interface Listener {
        void onSlideStateChanged();
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Layer[] layers = new Layer[2];
    private int front = 0;
    private final List<Uri> photos = new ArrayList<>();
    private int index = -1;
    private boolean paused;
    private boolean running;
    private Uri current;
    private Listener listener;
    private int loadToken;
    private int areaW = 1, areaH = 1;
    private boolean alignStart;

    /** Altura do "foco" ao cortar: 0 = topo, 0.5 = meio. Rostos ficam em geral um pouco acima do meio. */
    private static final float FOCO_VERTICAL = 0.38f;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!paused && !isPinned()) show(index + 1);
            ui.postDelayed(this, Prefs.intervalo(getContext()) * 1000L);
        }
    };

    /** Uma camada = fundo desfocado + foto inteira por cima (as duas camadas se revezam). */
    private final class Layer {
        final FrameLayout root = new FrameLayout(getContext());
        final ImageView bg = new ImageView(getContext());
        /** Área da foto nítida: o recuo (padding) define onde ela pode aparecer. */
        final FrameLayout area = new FrameLayout(getContext());
        final ImageView fg = new ImageView(getContext());

        Layer() {
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg.setColorFilter(0x80000000); // escurece o fundo para a foto e as janelas se destacarem
            if (Build.VERSION.SDK_INT >= 31) {
                bg.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        20, 20, android.graphics.Shader.TileMode.CLAMP));
            }
            fg.setScaleType(ImageView.ScaleType.FIT_CENTER);
            fg.setAdjustViewBounds(true);
            root.addView(bg, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            area.addView(fg, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER));
            root.addView(area, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    Slideshow(Context c) {
        super(c);
        for (int i = 0; i < 2; i++) {
            layers[i] = new Layer();
            layers[i].root.setAlpha(i == 0 ? 1f : 0f);
            addView(layers[i].root, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    /**
     * Área onde a foto nítida aparece (recuos a partir das bordas da tela). A foto fica
     * inteira e centralizada na altura dessa área; com alignStart, encostada à esquerda.
     * O fundo desfocado continua ocupando a tela toda.
     */
    void setPhotoInsets(int width, int height, int l, int t, int r, int b, boolean alignStart) {
        areaW = Math.max(1, width - l - r);
        areaH = Math.max(1, height - t - b);
        this.alignStart = alignStart;
        for (Layer layer : layers) {
            layer.area.setPadding(l, t, r, b);
            applyMode(layer);
        }
    }

    /** Arruma a foto da camada conforme o modo escolhido (preencher ou inteira). */
    private void applyMode(Layer layer) {
        LayoutParams lp = (LayoutParams) layer.fg.getLayoutParams();
        if (Prefs.fotoPreencher(getContext())) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            layer.fg.setAdjustViewBounds(false);
            layer.fg.setScaleType(ImageView.ScaleType.MATRIX);
            layer.fg.setLayoutParams(lp);
            fillMatrix(layer.fg);
        } else {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.gravity = (alignStart ? android.view.Gravity.START : android.view.Gravity.CENTER_HORIZONTAL)
                    | android.view.Gravity.CENTER_VERTICAL;
            layer.fg.setScaleType(ImageView.ScaleType.FIT_CENTER);
            layer.fg.setAdjustViewBounds(true);
            layer.fg.setMaxWidth(areaW);
            layer.fg.setMaxHeight(areaH);
            layer.fg.setLayoutParams(lp);
        }
    }

    /** Zoom para a foto cobrir a área inteira, cortando o mínimo e mirando no foco. */
    private void fillMatrix(ImageView v) {
        android.graphics.drawable.Drawable d = v.getDrawable();
        if (d == null) return;
        float bw = d.getIntrinsicWidth(), bh = d.getIntrinsicHeight();
        if (bw <= 0 || bh <= 0) return;
        float s = Math.max(areaW / bw, areaH / bh);
        float dx = (areaW - bw * s) * 0.5f;
        float dy = (areaH - bh * s) * FOCO_VERTICAL;
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate(dx, dy);
        v.setImageMatrix(m);
    }

    int count() {
        return photos.size();
    }

    boolean isPaused() {
        return paused;
    }

    boolean isPinned() {
        return Prefs.fotoFixada(getContext()) != null;
    }

    /** Carrega a lista de fotos (fotos soltas e pastas) e começa a passar. */
    void start() {
        running = true;
        final Context c = getContext().getApplicationContext();
        worker.execute(() -> {
            List<Uri> list = resolve(c);
            AppLog.i("Fotos: " + list.size() + " na rotação (" + Prefs.fontesFotos(c).size() + " origens).");
            ui.post(() -> {
                photos.clear();
                photos.addAll(list);
                String pin = Prefs.fotoFixada(c);
                if (pin != null) {
                    Uri pu = Uri.parse(pin);
                    // Guarda a posição da foto fixada para o "próxima" não repetir a mesma.
                    index = photos.indexOf(pu);
                    showUri(pu);
                } else if (!photos.isEmpty()) {
                    show(0);
                }
                notifyState();
            });
        });
        ui.removeCallbacks(advance);
        ui.postDelayed(advance, Prefs.intervalo(getContext()) * 1000L);
    }

    void stop() {
        running = false;
        ui.removeCallbacks(advance);
    }

    void next() {
        if (photos.isEmpty()) return;
        unpinSilently();
        show(index + 1);
        restartTimer();
    }

    void prev() {
        if (photos.isEmpty()) return;
        unpinSilently();
        show(index - 1);
        restartTimer();
    }

    void togglePause() {
        paused = !paused;
        notifyState();
    }

    /** Fixa a foto atual (ou solta, se já estiver fixada). */
    void togglePin() {
        if (isPinned()) {
            Prefs.setFotoFixada(getContext(), null);
        } else if (current != null) {
            Prefs.setFotoFixada(getContext(), current.toString());
        }
        notifyState();
    }

    private void unpinSilently() {
        if (isPinned()) Prefs.setFotoFixada(getContext(), null);
    }

    private void restartTimer() {
        ui.removeCallbacks(advance);
        if (running) ui.postDelayed(advance, Prefs.intervalo(getContext()) * 1000L);
        notifyState();
    }

    private void notifyState() {
        if (listener != null) listener.onSlideStateChanged();
    }

    private void show(int i) {
        if (photos.isEmpty()) return;
        int n = photos.size();
        index = ((i % n) + n) % n;
        showUri(photos.get(index));
    }

    /** Decodifica fora da tela principal e troca com um esmaecer suave. */
    private void showUri(Uri uri) {
        final int token = ++loadToken;
        final Context c = getContext().getApplicationContext();
        final int max = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        worker.execute(() -> {
            Bitmap full = decode(c, uri, max);
            if (full == null) {
                AppLog.i("Fotos: não consegui abrir " + uri);
                return;
            }
            Bitmap blurred = blurSmall(full);
            ui.post(() -> {
                if (token != loadToken) return;
                current = uri;
                Layer back = layers[1 - front];
                Layer top = layers[front];
                back.bg.setImageBitmap(blurred);
                back.fg.setImageBitmap(full);
                applyMode(back);
                back.root.animate().alpha(1f).setDuration(900).start();
                top.root.animate().alpha(0f).setDuration(900).start();
                front = 1 - front;
                notifyState();
            });
        });
    }

    /**
     * Cópia pequena e desfocada da foto para o fundo. Reduz para ~64px de largura e aplica
     * três passadas de desfoque em caixa: fica suave em qualquer Android e custa quase nada.
     */
    static Bitmap blurSmall(Bitmap src) {
        int w = 64;
        int h = Math.max(1, Math.round(64f * src.getHeight() / src.getWidth()));
        Bitmap b = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, 3, true);
            boxBlur(tmp, px, w, h, 3, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.max(0, Math.min(len - 1, i + k));
                    int p = horizontal ? in[l * w + j] : in[j * w + l];
                    rs += (p >> 16) & 255;
                    gs += (p >> 8) & 255;
                    bs += p & 255;
                    n++;
                }
                int o = horizontal ? l * w + i : i * w + l;
                out[o] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
            }
        }
    }

    // ---- Fotos no celular ----

    /** Junta as fotos soltas e as fotos de dentro das pastas escolhidas, em ordem aleatória. */
    static List<Uri> resolve(Context c) {
        List<Uri> out = new ArrayList<>();
        ContentResolver cr = c.getContentResolver();
        for (String item : Prefs.fontesFotos(c)) {
            Uri u = Uri.parse(item.substring(2));
            if (item.startsWith("f:")) {
                out.add(u);
            } else if (item.startsWith("d:")) {
                try {
                    String root = DocumentsContract.getTreeDocumentId(u);
                    collect(cr, u, root, out, 0);
                } catch (Exception ignored) {
                }
            }
            if (out.size() > 3000) break;
        }
        Collections.shuffle(out);
        return out;
    }

    private static void collect(ContentResolver cr, Uri tree, String docId, List<Uri> out, int depth) {
        if (depth > 3 || out.size() > 3000) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cur = cr.query(children, cols, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String mime = cur.getString(1);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collect(cr, tree, id, out, depth + 1);
                } else if (mime != null && mime.startsWith("image/")) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Lê a foto no tamanho da tela e corrige a rotação gravada pela câmera. */
    static Bitmap decode(Context c, Uri uri, int maxSide) {
        ContentResolver cr = c.getContentResolver();
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int side = Math.max(o.outWidth, o.outHeight);
            if (side <= 0) return null;
            int sample = 1;
            while (side / (sample * 2) >= maxSide) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = cr.openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o2);
            }
            if (b == null) return null;
            int deg = 0;
            if (Build.VERSION.SDK_INT >= 24) {
                try (InputStream in = cr.openInputStream(uri)) {
                    android.media.ExifInterface ex = new android.media.ExifInterface(in);
                    int or = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                    if (or == 6) deg = 90;
                    else if (or == 3) deg = 180;
                    else if (or == 8) deg = 270;
                } catch (Exception ignored) {
                }
            }
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}

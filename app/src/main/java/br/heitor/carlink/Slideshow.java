package br.heitor.carlink;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RenderEffect;
import android.graphics.Shader;
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
 * Papel de parede com as fotos do usuário passando. Fotos em pé e deitadas aparecem
 * inteiras: a foto fica centralizada e uma cópia desfocada dela preenche o resto da tela.
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

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!paused && !isPinned()) show(index + 1);
            ui.postDelayed(this, Prefs.intervalo(getContext()) * 1000L);
        }
    };

    /** Uma camada = fundo desfocado + foto inteira por cima. */
    private final class Layer {
        final FrameLayout root = new FrameLayout(getContext());
        final ImageView bg = new ImageView(getContext());
        final ImageView fg = new ImageView(getContext());

        Layer() {
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            fg.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (Build.VERSION.SDK_INT >= 31) {
                bg.setRenderEffect(RenderEffect.createBlurEffect(30, 30, Shader.TileMode.CLAMP));
            }
            bg.setAlpha(0.75f);
            root.addView(bg, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(fg, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
                    showUri(Uri.parse(pin));
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
            Bitmap small = Bitmap.createScaledBitmap(full, Math.max(1, full.getWidth() / 16),
                    Math.max(1, full.getHeight() / 16), true);
            ui.post(() -> {
                if (token != loadToken) return;
                current = uri;
                Layer back = layers[1 - front];
                Layer top = layers[front];
                back.bg.setImageBitmap(small);
                back.fg.setImageBitmap(full);
                back.root.animate().alpha(1f).setDuration(900).start();
                top.root.animate().alpha(0f).setDuration(900).start();
                front = 1 - front;
                notifyState();
            });
        });
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

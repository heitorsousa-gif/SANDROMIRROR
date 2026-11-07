package br.heitor.carlink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Estilo visual do modo direção: Windows 98 clássico (área de trabalho verde-azulada,
 * janelas e botões prateados, barra de título azul-marinho) com o conteúdo em tela preta
 * de terminal e texto neon. Fontes VT323 (terminal) e Orbitron (futurista).
 */
final class Retro {

    // Cores do tema (mudam conforme o tema escolhido nos Ajustes).
    /** Cor da área de trabalho (fundo da tela). */
    static int DESKTOP = Color.rgb(0, 128, 128);
    /** Cor dos botões e das janelas. */
    static int SILVER = Color.rgb(192, 192, 192);
    static final int LIGHT = Color.WHITE;
    static final int SHADOW = Color.rgb(128, 128, 128);
    static final int DARK = Color.rgb(10, 10, 10);
    /** Barra de título: degradê de NAVY até TITLE_END. */
    static int NAVY = Color.rgb(0, 0, 128);
    static int TITLE_END = Color.rgb(16, 132, 208);

    /** Esquemas de cores clássicos do Windows. */
    static final String[] THEME_NAMES = {"Windows Padrão", "Tijolo", "Deserto", "Windows 2000", "Escuro"};
    private static final int[][] THEMES = {
            // área de trabalho, botões/janelas, título início, título fim
            {0x008080, 0xC0C0C0, 0x000080, 0x1084D0},
            {0x5A1E12, 0xC2BFA5, 0x800000, 0xC04848},
            {0xA28D68, 0xD5CCBB, 0x008080, 0x48A8A8},
            {0x3A6EA5, 0xD4D0C8, 0x0A246A, 0xA6CAF0},
            {0x1C1C1C, 0xB4B4B4, 0x000000, 0x505050},
    };

    /** Aplica o tema guardado nas preferências. */
    static void applyTheme(Context c) {
        int i = Math.max(0, Math.min(THEMES.length - 1, Prefs.tema(c)));
        DESKTOP = 0xFF000000 | THEMES[i][0];
        SILVER = 0xFF000000 | THEMES[i][1];
        NAVY = 0xFF000000 | THEMES[i][2];
        TITLE_END = 0xFF000000 | THEMES[i][3];
    }

    /** Conteúdo das janelas: tela preta de terminal. */
    static final int SCREEN = Color.rgb(8, 8, 8);
    static final int GREEN = Color.rgb(57, 255, 20);
    static final int CYAN = Color.rgb(0, 229, 255);
    static final int YELLOW = Color.rgb(255, 214, 0);
    static final int RED = Color.rgb(255, 59, 48);
    /** Texto de destaque em botão prateado (ligado/fixado). */
    static final int ACTIVE = Color.rgb(170, 0, 0);

    private static Typeface term, future;

    private Retro() {
    }

    /** Fonte de terminal (VT323). */
    static Typeface term(Context c) {
        if (term == null) {
            try {
                term = Typeface.createFromAsset(c.getAssets(), "fonts/vt323.ttf");
            } catch (Exception e) {
                term = Typeface.MONOSPACE;
            }
        }
        return term;
    }

    /** Fonte futurista (Orbitron Black). */
    static Typeface future(Context c) {
        if (future == null) {
            try {
                future = Typeface.createFromAsset(c.getAssets(), "fonts/orbitron_black.ttf");
            } catch (Exception e) {
                future = Typeface.DEFAULT_BOLD;
            }
        }
        return future;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Texto com brilho neon. */
    static TextView text(Context c, String s, float sp, int color, Typeface tf, int glow) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        if (glow != 0) t.setShadowLayer(dp(c, 6), 0, 0, glow);
        return t;
    }

    /**
     * Borda em relevo estilo Windows 95: claro em cima/esquerda e escuro embaixo/direita
     * (ou o contrário quando afundado).
     */
    static final class Bevel extends Drawable {
        private final Paint p = new Paint();
        private final int fill, light, dark, t;

        Bevel(int fill, int light, int dark, int thickness) {
            this.fill = fill;
            this.light = light;
            this.dark = dark;
            this.t = thickness;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            p.setColor(fill);
            c.drawRect(b, p);
            p.setColor(light);
            c.drawRect(b.left, b.top, b.right, b.top + t, p);
            c.drawRect(b.left, b.top, b.left + t, b.bottom, p);
            p.setColor(dark);
            c.drawRect(b.left, b.bottom - t, b.right, b.bottom, p);
            c.drawRect(b.right - t, b.top, b.right, b.bottom, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Fundo de botão que afunda quando tocado. */
    static Drawable buttonBg(Context c, int fill) {
        int t = dp(c, 2);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new Bevel(fill, DARK, LIGHT, t));
        s.addState(new int[]{}, new Bevel(fill, LIGHT, DARK, t));
        return s;
    }

    static int darker(int c) {
        return Color.rgb(Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10);
    }

    /** Botão de texto retrô. */
    static TextView button(Context c, String label, int fill, View.OnClickListener l) {
        TextView t = text(c, label, 21, Color.BLACK, term(c), 0);
        t.setGravity(Gravity.CENTER);
        int ph = dp(c, 10), pv = dp(c, 6);
        t.setPadding(ph, pv, ph, pv);
        t.setBackground(buttonBg(c, fill));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    /** Botão quadrado com ícone (vetor) neon. */
    static ImageView iconButton(Context c, int iconRes, int tint, View.OnClickListener l) {
        ImageView v = new ImageView(c);
        v.setImageResource(iconRes);
        v.setColorFilter(tint);
        int p = dp(c, 10);
        v.setPadding(p, p, p, p);
        v.setBackground(buttonBg(c, SILVER));
        v.setClickable(true);
        v.setOnClickListener(l);
        return v;
    }

    /**
     * Janela estilo Windows 95: barra de título em degradê neon com os botõezinhos
     * de minimizar e fechar (decorativos) e o conteúdo embaixo.
     */
    static LinearLayout window(Context c, String title, View content) {
        LinearLayout w = new LinearLayout(c);
        w.setOrientation(LinearLayout.VERTICAL);
        int t = dp(c, 3);
        w.setPadding(t, t, t, t);
        w.setBackground(new Bevel(SILVER, LIGHT, DARK, t));

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{NAVY, TITLE_END}));
        int pb = dp(c, 6);
        bar.setPadding(pb, dp(c, 2), dp(c, 3), dp(c, 2));
        TextView tt = text(c, title, 18, Color.WHITE, term(c), 0);
        bar.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (String s : new String[]{"_", "X"}) {
            TextView b = text(c, s, 14, Color.BLACK, term(c), 0);
            b.setGravity(Gravity.CENTER);
            b.setBackground(new Bevel(SILVER, LIGHT, DARK, dp(c, 1)));
            int sz = dp(c, 18);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.setMargins(dp(c, 3), 0, 0, 0);
            bar.addView(b, lp);
        }
        w.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int pc = dp(c, 10);
        content.setPadding(pc, pc, pc, pc);
        // Área de conteúdo afundada e preta, como uma tela de terminal dentro da janela.
        content.setBackground(new Bevel(SCREEN, SHADOW, LIGHT, dp(c, 2)));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMargins(0, dp(c, 3), 0, 0);
        w.addView(content, cl);
        return w;
    }

    /** Linhas de tela de tubo (CRT), bem suaves, por cima de tudo. */
    static final class Scanlines extends Drawable {
        private final Paint p = new Paint();
        private final int gap;

        Scanlines(Context c) {
            p.setColor(Color.argb(22, 0, 0, 0));
            gap = Math.max(3, dp(c, 2));
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            for (int y = b.top; y < b.bottom; y += gap) c.drawRect(b.left, y, b.right, y + 1, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}

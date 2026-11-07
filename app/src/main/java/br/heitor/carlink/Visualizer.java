package br.heitor.carlink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * Barrinhas de equalizador estilo anos 90 (verde, amarelo e vermelho, como os medidores antigos). É só decoração (não lê o áudio de verdade):
 * as barras dançam enquanto a música toca e descem quando pausa. Atualiza ~6 vezes por
 * segundo para gastar pouco e não atrapalhar o espelhamento.
 */
class Visualizer extends View {

    private static final int BARS = 16;
    private final float[] h = new float[BARS];
    private final float[] target = new float[BARS];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private boolean playing;
    private int lastH;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            boolean moving = false;
            for (int i = 0; i < BARS; i++) {
                if (playing && rnd.nextInt(3) == 0) target[i] = 0.15f + rnd.nextFloat() * 0.85f;
                if (!playing) target[i] = 0.06f;
                h[i] += (target[i] - h[i]) * 0.45f;
                if (Math.abs(target[i] - h[i]) > 0.01f) moving = true;
            }
            invalidate();
            if (playing || moving) postDelayed(this, 160);
        }
    };

    Visualizer(Context c) {
        super(c);
        for (int i = 0; i < BARS; i++) h[i] = 0.06f;
    }

    void setPlaying(boolean v) {
        if (playing == v) return;
        playing = v;
        removeCallbacks(frame);
        post(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), hh = getHeight();
        if (w == 0 || hh == 0) return;
        if (hh != lastH) {
            p.setShader(new LinearGradient(0, hh, 0, 0, new int[]{Retro.GREEN, Retro.YELLOW, Retro.RED},
                    null, Shader.TileMode.CLAMP));
            lastH = hh;
        }
        float slot = (float) w / BARS;
        float gap = slot * 0.25f;
        // Cada barra é feita de "bloquinhos", como os equalizadores antigos.
        float block = Math.max(3, hh / 12f);
        for (int i = 0; i < BARS; i++) {
            float top = hh - h[i] * hh;
            float x0 = i * slot + gap / 2, x1 = (i + 1) * slot - gap / 2;
            for (float y = hh; y > top; y -= block) {
                c.drawRect(x0, Math.max(top, y - block + 2), x1, y, p);
            }
        }
    }
}

package br.heitor.carlink;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

import java.util.ArrayList;
import java.util.List;

/** Preferências do app guardadas no celular. */
final class Prefs {

    private Prefs() {
    }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("sandro", Context.MODE_PRIVATE);
    }

    // ---- Modo central (deitado) ou modo celular (copiloto, em pé) ----

    static boolean modoCelular(Context c) {
        return sp(c).getBoolean("modoCelular", false);
    }

    static void setModoCelular(Context c, boolean v) {
        sp(c).edit().putBoolean("modoCelular", v).apply();
    }

    /** Aplica a orientação da tela conforme o modo escolhido. */
    static void applyOrientation(Activity a) {
        a.setRequestedOrientation(modoCelular(a)
                ? ActivityInfo.SCREEN_ORIENTATION_USER
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /** "Apagar tela": deixa a tela do celular no brilho mínimo (a central continua normal). */
    static boolean apagarTela(Context c) {
        return sp(c).getBoolean("apagarTela", false);
    }

    static void setApagarTela(Context c, boolean v) {
        sp(c).edit().putBoolean("apagarTela", v).apply();
    }

    /**
     * Aplica o "apagar tela" na janela do app: brilho mínimo quando ligado, brilho normal
     * quando desligado. O brilho da tela do celular não muda a imagem que vai para a central.
     */
    static void applyBrightness(Activity a) {
        android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        float want = apagarTela(a) ? 0.01f : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (lp.screenBrightness != want) {
            lp.screenBrightness = want;
            a.getWindow().setAttributes(lp);
        }
    }

    /** Tema de cores (índice em Retro.THEME_NAMES). */
    static int tema(Context c) {
        return sp(c).getInt("tema", 0);
    }

    static void setTema(Context c, int i) {
        sp(c).edit().putInt("tema", i).apply();
    }

    // ---- Fotos do papel de parede ----
    // Cada item é "f:<uri>" (uma foto) ou "d:<uri>" (uma pasta inteira).

    static List<String> fontesFotos(Context c) {
        String s = sp(c).getString("fotos", "");
        List<String> out = new ArrayList<>();
        for (String line : s.split("\n")) if (line.length() > 2) out.add(line);
        return out;
    }

    static void addFonteFoto(Context c, String item) {
        List<String> l = fontesFotos(c);
        if (!l.contains(item)) l.add(item);
        sp(c).edit().putString("fotos", android.text.TextUtils.join("\n", l)).apply();
    }

    static void limparFotos(Context c) {
        sp(c).edit().remove("fotos").remove("fotoFixada").apply();
    }

    /** Foto fixada (null = as fotos ficam passando). */
    static String fotoFixada(Context c) {
        return sp(c).getString("fotoFixada", null);
    }

    static void setFotoFixada(Context c, String uri) {
        if (uri == null) sp(c).edit().remove("fotoFixada").apply();
        else sp(c).edit().putString("fotoFixada", uri).apply();
    }

    /** Segundos entre uma foto e outra. */
    static int intervalo(Context c) {
        return sp(c).getInt("intervalo", 15);
    }

    static void setIntervalo(Context c, int s) {
        sp(c).edit().putInt("intervalo", s).apply();
    }
}

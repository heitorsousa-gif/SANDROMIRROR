package br.heitor.carlink;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Procura a versão mais nova no GitHub Releases e instala por cima.
 * Só acessa a internet quando o usuário toca em "Atualizar".
 */
final class Updater {

    static final String REPO = "heitorsousa-gif/SANDROMIRROR";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";
    static final String ACTION_INSTALL_STATUS = "br.heitor.carlink.INSTALL_STATUS";
    private static final String TAG_PREFIX = "build-";

    private Updater() {
    }

    static void check(Activity a) {
        AppLog.i("Procurando atualização...");
        new Thread(() -> {
            try {
                String json = new String(get("https://api.github.com/repos/" + REPO + "/releases/latest"), "UTF-8");
                JSONObject rel = new JSONObject(json);
                String tag = rel.getString("tag_name");
                int remote = tag.startsWith(TAG_PREFIX) ? Integer.parseInt(tag.substring(TAG_PREFIX.length())) : 0;
                String url = null;
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (as.getString("name").endsWith(".apk")) url = as.getString("browser_download_url");
                }
                int local = localBuild(a);
                AppLog.i("Versão instalada: build " + local + ". Mais nova no GitHub: build " + remote + ".");
                final String apkUrl = url;
                a.runOnUiThread(() -> {
                    if (remote <= local || apkUrl == null) {
                        new AlertDialog.Builder(a).setMessage("Você já está na versão mais nova (build " + local + ").")
                                .setPositiveButton("OK", null).show();
                    } else {
                        new AlertDialog.Builder(a)
                                .setTitle("Nova versão disponível")
                                .setMessage("Build " + remote + " (você tem a build " + local + "). Atualizar agora?")
                                .setPositiveButton("Atualizar", (d, w) -> download(a, apkUrl))
                                .setNegativeButton("Depois", null).show();
                    }
                });
            } catch (Exception e) {
                AppLog.i("Não consegui consultar o GitHub direto (" + e.getMessage()
                        + "). Abrindo a página de versões no navegador.");
                a.runOnUiThread(() -> openReleasesPage(a));
            }
        }, "update-check").start();
    }

    static int localBuild(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static void openReleasesPage(Activity a) {
        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)));
    }

    private static void download(Activity a, String url) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            AppLog.i("Falta liberar 'Instalar apps desconhecidos' para o CarLink. Libere e toque em Atualizar de novo.");
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return;
        }
        AppLog.i("Baixando a nova versão...");
        new Thread(() -> {
            File apk = new File(a.getCacheDir(), "update.apk");
            try {
                byte[] data = get(url);
                try (FileOutputStream fo = new FileOutputStream(apk)) {
                    fo.write(data);
                }
                AppLog.i("Download concluído (" + (data.length / 1024) + " KB). Instalando...");
                install(a, apk);
            } catch (Exception e) {
                AppLog.i("Falha ao baixar/instalar: " + e.getMessage() + ". Abrindo a página no navegador.");
                a.runOnUiThread(() -> openReleasesPage(a));
            }
        }, "update-download").start();
    }

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("carlink", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(ACTION_INSTALL_STATUS).setPackage(c.getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent p = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(p.getIntentSender());
        }
    }

    /** Trata o retorno do instalador (pedido de confirmação, sucesso ou erro). */
    static void onInstallStatus(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(confirm);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            AppLog.i("Atualização instalada.");
        } else {
            AppLog.i("Instalação falhou: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    private static byte[] get(String url) throws IOException {
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
            con.setRequestProperty("User-Agent", "CarLink-updater");
            con.setInstanceFollowRedirects(false);
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                url = con.getHeaderField("Location");
                con.disconnect();
                continue;
            }
            if (code != 200) {
                con.disconnect();
                throw new IOException("HTTP " + code + (code == 404 ? " (repositório privado?)" : ""));
            }
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            } finally {
                con.disconnect();
            }
        }
        throw new IOException("redirecionamentos demais");
    }
}

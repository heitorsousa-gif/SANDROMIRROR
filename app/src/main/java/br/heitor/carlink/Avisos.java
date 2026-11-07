package br.heitor.carlink;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Lê as notificações para o modo direção: a próxima instrução do Google Maps.
 * Ter este serviço liberado também é o que permite ler a música que está tocando
 * (MediaSessionManager exige um leitor de notificações ativo).
 */
public class Avisos extends NotificationListenerService {

    interface Listener {
        void onNavChanged();
    }

    /** Navegação atual do Maps (null = sem rota). */
    static volatile String navTitle;
    static volatile String navText;
    static volatile Drawable navIcon;
    private static volatile Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void setListener(Listener l) {
        listener = l;
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, Avisos.class);
    }

    /** O usuário já liberou "Acesso a notificações" para o app? */
    static boolean enabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    @Override
    public void onListenerConnected() {
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) onNotificationPosted(sbn);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!DashActivity.MAPS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        // A notificação de navegação do Maps é contínua (ongoing).
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return;
        Bundle ex = n.extras;
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return;
        navTitle = title == null ? null : title.toString();
        navText = text == null ? null : text.toString();
        Drawable icon = null;
        try {
            Icon large = n.getLargeIcon();
            if (large != null) icon = large.loadDrawable(this);
        } catch (Exception ignored) {
        }
        navIcon = icon;
        notifyListener();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!DashActivity.MAPS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return;
        navTitle = null;
        navText = null;
        navIcon = null;
        notifyListener();
    }

    private static void notifyListener() {
        Listener l = listener;
        if (l != null) MAIN.post(l::onNavChanged);
    }
}

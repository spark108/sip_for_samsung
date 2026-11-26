package ru.spark108.sipphoneservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.util.Log;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import org.linphone.core.Account;
import org.linphone.core.AccountParams;
import org.linphone.core.Address;
import org.linphone.core.Call;
import org.linphone.core.Core;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.Factory;
import org.linphone.core.RegistrationState;

public final class SipService extends Service {
    private static final String TAG = "SIPPhoneService";
    static final String CHANNEL = "sip_registration";
    private static final String ACTION_STOP = "stop";
    static final String ACTION_STATUS = "ru.spark108.sipphoneservice.STATUS";
    private static SipService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Call> incoming = new HashMap<>();
    private final Map<Call, SipConnectionService.SipConnection> connections = new IdentityHashMap<>();
    private Core core;
    private CoreListenerStub coreListener;
    private Config config;
    private int status = R.string.sip_registering;

    static SipService instance() { return active; }
    boolean ready() { return core != null; }
    int status() { return status; }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Регистрация SIP", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        try {
            int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // MainActivity starts the service while visible and after granting RECORD_AUDIO.
                serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(1, notification(status), serviceTypes);
            if (core != null) {
                notifyStatusChanged();
                return START_STICKY;
            }
            return reloadConfig() ? START_STICKY : START_NOT_STICKY;
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot start SIP foreground service", e);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    boolean reloadConfig() {
        stopCore();
        config = Config.load(this);
        if (!config.valid()) {
            stopSelf();
            return false;
        }
        try {
            SipConnectionService.register(this);
            startCore();
            updateStatus(R.string.sip_registering);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Cannot start SIP", e);
            stopSelf();
            return false;
        }
    }

    private void startCore() {
        Core startedCore = Factory.instance().createCore(null, null, getApplicationContext());
        core = startedCore;
        core.setNativeRingingEnabled(false);
        // Android Telecom manages audio focus and hold for the managed PhoneAccount.
        core.getConfig().setBool("audio", "android_disable_audio_focus_requests", true);
        core.getConfig().setBool("audio", "android_pause_calls_when_audio_focus_lost", false);
        coreListener = new CoreListenerStub() {
            @Override public void onAccountRegistrationStateChanged(Core c, Account account, RegistrationState state, String message) {
                main.post(() -> {
                    if (core != startedCore) return;
                    updateStatus(switch (state) {
                        case Ok, Refreshing -> R.string.sip_registered;
                        case Progress -> R.string.sip_registering;
                        default -> R.string.sip_offline;
                    });
                });
            }
            @Override public void onCallStateChanged(Core c, Call call, Call.State state, String message) {
                main.post(() -> {
                    if (core == startedCore) callState(call, state, message);
                });
            }
        };
        core.addListener(coreListener);
        Address identity = Factory.instance().createAddress("sip:" + config.user + "@" + config.domain);
        String server = config.server.isEmpty() ? config.domain : config.server;
        Address proxy = Factory.instance().createAddress(server.startsWith("sip:") || server.startsWith("sips:") ? server : "sip:" + server);
        if (identity == null || proxy == null) throw new IllegalArgumentException("Неверный SIP адрес");
        AccountParams params = core.createAccountParams();
        if (params.setIdentityAddress(identity) != 0 || params.setServerAddress(proxy) != 0) throw new IllegalArgumentException("Неверные параметры SIP");
        params.setRegisterEnabled(true);
        core.addAuthInfo(Factory.instance().createAuthInfo(config.user, null, config.password, null, null, config.domain));
        Account account = core.createAccount(params);
        if (core.addAccount(account) != 0) throw new IllegalStateException("Cannot add SIP account");
        core.setDefaultAccount(account);
        if (core.start() != 0) throw new IllegalStateException("Cannot start Linphone core");
    }

    private void callState(Call call, Call.State state, String message) {
        SipConnectionService.SipConnection connection = connections.get(call);
        if (state == Call.State.IncomingReceived) {
            String id = UUID.randomUUID().toString();
            incoming.put(id, call);
            Bundle extras = new Bundle();
            extras.putString(SipConnectionService.EXTRA_CALL_ID, id);
            try { ((TelecomManager) getSystemService(TELECOM_SERVICE)).addNewIncomingCall(SipConnectionService.handle(this), extras); }
            catch (RuntimeException e) { Log.e(TAG, "Incoming Telecom call rejected", e); rejectIncoming(id); }
            return;
        }
        if (state == Call.State.End || state == Call.State.Error || state == Call.State.Released) {
            incoming.values().removeIf(pending -> pending == call);
        }
        if (connection == null) return;
        switch (state) {
            case OutgoingProgress:
            case OutgoingRinging:
                connection.setDialing(); break;
            case Connected:
            case StreamsRunning:
                connection.setActive(); break;
            case Paused:
            case PausedByRemote:
                connection.setOnHold(); break;
            case End:
            case Error:
            case Released:
                connection.end(state == Call.State.Error ? DisconnectCause.ERROR : connection.endCause(), message);
                connections.remove(call);
                break;
            default: break;
        }
    }

    Call takeIncoming(String id) {
        Call call = incoming.remove(id);
        if (call == null) return null;
        Call.State state = call.getState();
        return state == Call.State.IncomingReceived || state == Call.State.IncomingEarlyMedia ? call : null;
    }

    void rejectIncoming(String id) {
        Call call = takeIncoming(id);
        if (call != null) call.terminate();
    }

    void attach(Call call, SipConnectionService.SipConnection connection) { connections.put(call, connection); }
    void dial(Uri address, SipConnectionService.SipConnection connection) {
        Core dialCore = core;
        main.post(() -> {
            if (connection.getState() == Connection.STATE_DISCONNECTED) return;
            if (core == null || core != dialCore) {
                connection.end(DisconnectCause.ERROR, null);
                return;
            }
            String uri = PhoneAccount.SCHEME_TEL.equalsIgnoreCase(address.getScheme())
                ? "sip:" + address.getSchemeSpecificPart() + "@" + config.domain
                : address.toString();
            Call call = core.invite(uri);
            if (call == null) {
                connection.end(DisconnectCause.ERROR, "Не удалось создать SIP-вызов");
                return;
            }
            connection.bind(call);
            connections.put(call, connection);
            connection.setDialing();
        });
    }
    private void updateStatus(int text) {
        if (status != text) {
            status = text;
            getSystemService(NotificationManager.class).notify(1, notification(status));
        }
        notifyStatusChanged();
    }

    private void notifyStatusChanged() {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
    }

    private Notification notification(int text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SipService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle(getText(R.string.app_name)).setContentText(getText(text)).setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stop).setOngoing(true).build();
    }

    private void stopCore() {
        for (SipConnectionService.SipConnection connection : connections.values()) {
            connection.end(DisconnectCause.LOCAL, null);
        }
        connections.clear();
        incoming.clear();
        Core previousCore = core;
        core = null;
        if (previousCore != null) {
            if (coreListener != null) previousCore.removeListener(coreListener);
            previousCore.stop();
        }
        coreListener = null;
    }

    @Override public void onDestroy() {
        active = null;
        stopCore();
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifyStatusChanged();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

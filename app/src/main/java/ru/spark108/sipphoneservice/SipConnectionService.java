package ru.spark108.sipphoneservice;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;
import org.linphone.core.Address;
import org.linphone.core.Call;

public final class SipConnectionService extends ConnectionService {
    private static final int ACCOUNT_ICON_SIZE = 64;
    static final String ACCOUNT_ID = "sip-account";
    static final String EXTRA_CALL_ID = "sip_call_id";

    static PhoneAccountHandle handle(Context context) {
        return new PhoneAccountHandle(new ComponentName(context, SipConnectionService.class), ACCOUNT_ID);
    }

    static void register(Context context, Config config) {
        if (!config.valid()) return;

        String name = config.displayName.isEmpty() ? config.user : config.displayName;
        int characters = Math.min(name.codePointCount(0, name.length()), 100);
        String label = context.getString(R.string.phone_account_label, name.substring(0, name.offsetByCodePoints(0, characters)));
        PhoneAccount.Builder account = new PhoneAccount.Builder(handle(context), label)
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .setSupportedUriSchemes(java.util.Arrays.asList(PhoneAccount.SCHEME_TEL, PhoneAccount.SCHEME_SIP));
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        try {
            telecom.registerPhoneAccount(account.setIcon(accountIcon(context)).build());
        } catch (IllegalArgumentException e) {
            Log.w("SIPPhoneService", "Telecom rejected the phone account icon", e);
            telecom.registerPhoneAccount(account.setIcon(null).build());
        }
    }

    private static Icon accountIcon(Context context) {
        Drawable launcher = context.getDrawable(R.mipmap.ic_launcher);
        Bitmap bitmap = Bitmap.createBitmap(ACCOUNT_ICON_SIZE, ACCOUNT_ICON_SIZE, Bitmap.Config.ARGB_8888);
        launcher.setBounds(0, 0, ACCOUNT_ICON_SIZE, ACCOUNT_ICON_SIZE);
        launcher.draw(new Canvas(bitmap));
        return Icon.createWithBitmap(bitmap);
    }

    @Override public Connection onCreateOutgoingConnection(PhoneAccountHandle account, ConnectionRequest request) {
        SipService engine = SipService.instance();
        if (engine == null || !engine.ready()) return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR, "SIP выключен"));
        Uri address = request.getAddress();
        if (address == null || address.getSchemeSpecificPart() == null || address.getSchemeSpecificPart().isEmpty()
                || !(PhoneAccount.SCHEME_TEL.equalsIgnoreCase(address.getScheme())
                    || PhoneAccount.SCHEME_SIP.equalsIgnoreCase(address.getScheme())
                    || "sips".equalsIgnoreCase(address.getScheme()))) {
            return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR));
        }
        SipConnection connection = new SipConnection(null);
        connection.setAddress(address, TelecomManager.PRESENTATION_ALLOWED);
        connection.setInitializing();
        engine.dial(address, connection);
        return connection;
    }

    @Override public Connection onCreateIncomingConnection(PhoneAccountHandle account, ConnectionRequest request) {
        SipService engine = SipService.instance();
        if (engine == null) return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR));
        Call call = engine.takeIncoming(incomingId(request));
        if (call == null) return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR));
        SipConnection connection = new SipConnection(call);
        Address from = call.getRemoteAddress();
        if (from != null) connection.setAddress(Uri.parse(from.asStringUriOnly()), TelecomManager.PRESENTATION_ALLOWED);
        connection.setRinging();
        engine.attach(call, connection);
        return connection;
    }

    @Override public void onCreateIncomingConnectionFailed(PhoneAccountHandle account, ConnectionRequest request) {
        SipService engine = SipService.instance();
        if (engine != null) engine.rejectIncoming(incomingId(request));
    }

    private static String incomingId(ConnectionRequest request) {
        Bundle extras = request.getExtras();
        return extras == null ? null : extras.getString(EXTRA_CALL_ID);
    }

    static final class SipConnection extends Connection {
        private Call call;
        private boolean disconnectRequested;

        SipConnection(Call call) {
            this.call = call;
            setAudioModeIsVoip(true);
            setConnectionCapabilities(CAPABILITY_MUTE | CAPABILITY_SUPPORT_HOLD | CAPABILITY_HOLD);
        }
        void bind(Call value) { call = value; }

        int endCause() { return disconnectRequested ? DisconnectCause.LOCAL : DisconnectCause.REMOTE; }

        void end(int cause, String message) {
            call = null;
            setDisconnected(new DisconnectCause(cause, message));
            destroy();
        }

        private void hangup() {
            if (getState() == STATE_DISCONNECTED) return;
            disconnectRequested = true;
            if (call == null) end(DisconnectCause.CANCELED, null);
            else call.terminate();
        }

        @Override public void onAnswer() { if (call != null) call.accept(); }
        @Override public void onReject() { hangup(); }
        @Override public void onDisconnect() { hangup(); }
        @Override public void onAbort() { hangup(); }
        @Override public void onHold() { if (call != null) call.pause(); }
        @Override public void onUnhold() { if (call != null) call.resume(); }
        @Override public void onPlayDtmfTone(char digit) { if (call != null) call.sendDtmf(digit); }
    }
}

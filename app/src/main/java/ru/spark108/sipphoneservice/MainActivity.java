package ru.spark108.sipphoneservice;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

public final class MainActivity extends Activity {
    private static final int REQUEST_MICROPHONE = 1;
    private Switch enable;
    private TextView status;
    private LinearLayout accountList;
    private boolean updating;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateStatus(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Config config = Config.load(this);
        if (config.valid()) SipConnectionService.register(this);
        setContentView(R.layout.activity_main);
        enable = findViewById(R.id.enable);
        status = findViewById(R.id.status);
        accountList = findViewById(R.id.account_list);
        row(findViewById(R.id.accounts_row), "Учётные записи для вызовов", "Выбрать SIP в приложении «Телефон»", () -> {
            try { startActivity(new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        row(findViewById(R.id.notifications_row), "Уведомления SIP", "Настройки уведомлений Samsung", () -> {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        });
        enable.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            if (checked) connect();
            else {
                if (!stopService(new Intent(this, SipService.class))) updateStatus();
                else enable.setEnabled(false);
            }
        });
        IntentFilter filter = new IntentFilter(SipService.ACTION_STATUS);
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void row(View view, String title, String summary, Runnable click) {
        ((TextView) view.findViewById(R.id.row_title)).setText(title);
        TextView sub = view.findViewById(R.id.row_summary);
        sub.setText(summary);
        sub.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        view.setOnClickListener(v -> click.run());
    }

    private void renderAccount() {
        Config config = Config.load(this);
        accountList.removeAllViews();
        View view = LayoutInflater.from(this).inflate(R.layout.setting_row, accountList, false);
        row(view, config.user.isEmpty() ? "Настроить SIP-аккаунт" : config.user + "@" + config.domain,
            config.valid() ? "Параметры подключения" : "Укажите логин, домен и пароль",
            () -> startActivity(new Intent(this, AccountActivity.class)));
        accountList.addView(view);
    }

    private void connect() {
        if (!Config.load(this).valid()) {
            Toast.makeText(this, "Настройте SIP-аккаунт", Toast.LENGTH_LONG).show();
            updateStatus();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_MICROPHONE);
        else startSip();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != REQUEST_MICROPHONE) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSip();
        else { Toast.makeText(this, "Для звонков нужен доступ к микрофону", Toast.LENGTH_LONG).show(); updateStatus(); }
    }

    private void startSip() {
        try {
            startForegroundService(new Intent(this, SipService.class));
            status.setText(R.string.sip_registering);
            updating = true;
            enable.setChecked(true);
            updating = false;
            enable.setEnabled(false);
        } catch (IllegalStateException | SecurityException e) {
            Toast.makeText(this, "Не удалось запустить SIP", Toast.LENGTH_LONG).show();
            updateStatus();
        }
    }

    private void updateStatus() {
        SipService service = SipService.instance();
        status.setText(service == null ? R.string.sip_offline : service.status());
        if (enable.isChecked() != (service != null)) {
            updating = true;
            enable.setChecked(service != null);
            updating = false;
        }
        enable.setEnabled(true);
    }

    @Override public void onResume() {
        super.onResume();
        renderAccount();
        updateStatus();
    }

    @Override public void onDestroy() {
        unregisterReceiver(statusReceiver);
        super.onDestroy();
    }
}

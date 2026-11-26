package ru.spark108.sipphoneservice;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

public final class PermissionsActivity extends Activity {
    private static final int REQUEST_MICROPHONE = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_permissions);
        SettingsUi.header(this, R.string.permissions_title);
        SettingsUi.row(findViewById(R.id.call_permissions_row), getString(R.string.call_accounts_title),
            getString(R.string.call_accounts_purpose), this::openCallAccounts);
    }

    @Override public void onResume() {
        super.onResume();
        refresh();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        SettingsUi.recordPermissionResult(this, permissions, grants);
        refresh();
    }

    private void refresh() {
        permissionRow(R.id.microphone_row, R.string.microphone_title, R.string.microphone_purpose,
            SettingsUi.granted(this, Manifest.permission.RECORD_AUDIO),
            () -> SettingsUi.requestOrOpen(this, Manifest.permission.RECORD_AUDIO, REQUEST_MICROPHONE, SettingsUi.appDetails(this)));
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(SipService.CHANNEL);
        boolean notificationsEnabled = manager.areNotificationsEnabled()
            && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
        permissionRow(R.id.notifications_row, R.string.notifications_title, R.string.notifications_purpose,
            notificationsEnabled, this::notifications);
        permissionRow(R.id.install_updates_row, R.string.install_updates_title, R.string.install_updates_purpose,
            getPackageManager().canRequestPackageInstalls(), () -> SettingsUi.open(this, SettingsUi.installSettings(this)));
    }

    private void permissionRow(int row, int title, int purpose, boolean allowed, Runnable click) {
        String status = getString(allowed ? R.string.permission_allowed : R.string.permission_denied);
        SettingsUi.row(findViewById(row), getString(title), getString(R.string.permission_summary, status, getString(purpose)), click);
    }

    private void notifications() {
        Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SettingsUi.requestOrOpen(this, Manifest.permission.POST_NOTIFICATIONS, REQUEST_NOTIFICATIONS, settings);
        } else {
            SettingsUi.open(this, settings);
        }
    }

    private void openCallAccounts() {
        if (Config.load(this).valid()) SipConnectionService.register(this);
        SettingsUi.openCallAccounts(this);
    }
}

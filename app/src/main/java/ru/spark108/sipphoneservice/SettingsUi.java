package ru.spark108.sipphoneservice;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

final class SettingsUi {
    private SettingsUi() { }

    static void header(Activity activity, int title) {
        activity.<TextView>findViewById(R.id.settings_title).setText(title);
        activity.findViewById(R.id.settings_back).setOnClickListener(v -> activity.finish());
    }

    static void row(View view, String title, String summary, Runnable click) {
        view.<TextView>findViewById(R.id.row_title).setText(title);
        TextView subtitle = view.findViewById(R.id.row_summary);
        subtitle.setText(summary);
        subtitle.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        view.setOnClickListener(v -> click.run());
    }

    static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static void requestOrOpen(Activity activity, String permission, int request, Intent settings) {
        boolean denied = permissionHistory(activity).getBoolean(permission, false);
        if (granted(activity, permission) || (denied && !activity.shouldShowRequestPermissionRationale(permission))) {
            open(activity, settings);
        } else {
            activity.requestPermissions(new String[] { permission }, request);
        }
    }

    static void recordPermissionResult(Context context, String[] permissions, int[] grants) {
        if (permissions.length != 1 || grants.length != 1) return;
        permissionHistory(context).edit().putBoolean(permissions[0], grants[0] != PackageManager.PERMISSION_GRANTED).apply();
    }

    private static SharedPreferences permissionHistory(Context context) {
        return context.getSharedPreferences("permission_requests", Context.MODE_PRIVATE);
    }

    static Intent appDetails(Context context) { return packageSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS); }
    static Intent installSettings(Context context) { return packageSettings(context, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES); }

    private static Intent packageSettings(Context context, String action) {
        return new Intent(action, Uri.fromParts("package", context.getPackageName(), null));
    }

    static void openCallAccounts(Activity activity) {
        try {
            activity.startActivity(new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS));
        } catch (ActivityNotFoundException | SecurityException e) {
            open(activity, new Intent(Settings.ACTION_SETTINGS));
        }
    }

    static void open(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.settings_unavailable, Toast.LENGTH_SHORT).show();
        }
    }
}

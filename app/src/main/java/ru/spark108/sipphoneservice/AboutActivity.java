package ru.spark108.sipphoneservice;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AboutActivity extends Activity {
    private static final String TAG = "SIPUpdates";
    private static final String AWAITING_PERMISSION = "awaiting_install_permission";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private ProgressBar progress;
    private Button action;
    private PackageInfo installed;
    private GitHubUpdates.Release release;
    private File verifiedApk;
    private boolean awaitingPermission;
    private boolean resumed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_about);
        SettingsUi.header(this, R.string.about_title);
        SettingsUi.row(findViewById(R.id.source_code_row), getString(R.string.about_source_code), "GitHub", this::openSourceCode);
        status = findViewById(R.id.update_status);
        progress = findViewById(R.id.update_progress);
        action = findViewById(R.id.update_action);
        awaitingPermission = state != null && state.getBoolean(AWAITING_PERMISSION);
        try {
            installed = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            TextView version = findViewById(R.id.about_version);
            version.setText(getString(R.string.about_version, installed.versionName));
            checkForUpdate();
        } catch (PackageManager.NameNotFoundException e) {
            status.setText(R.string.update_check_failed);
        }
    }

    private void openSourceCode() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdates.REPOSITORY_URL)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.about_source_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        continueAfterPermission();
    }

    @Override public void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean(AWAITING_PERMISSION, awaitingPermission);
        super.onSaveInstanceState(state);
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void checkForUpdate() {
        show(R.string.update_checking, 0, null);
        worker.execute(() -> {
            try {
                GitHubUpdates.Check result = GitHubUpdates.check(installed.versionName);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    release = result.release();
                    switch (result.status()) {
                        case NO_RELEASE -> show(R.string.update_no_release, 0, null);
                        case CURRENT -> show(R.string.update_latest, 0, null);
                        case MISSING_APK -> show(getString(R.string.update_missing_asset, result.version()), 0, null);
                        case AVAILABLE -> {
                            show(getString(R.string.update_available, result.version()), R.string.update_action, this::update);
                            continueAfterPermission();
                        }
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Release check failed", e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) show(R.string.update_check_failed, R.string.update_retry, this::checkForUpdate);
                });
            }
        });
    }

    private void update() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            try {
                awaitingPermission = true;
                startActivity(SettingsUi.installSettings(this));
            } catch (ActivityNotFoundException | SecurityException e) {
                awaitingPermission = false;
                show(R.string.settings_unavailable, R.string.update_action, this::update);
            }
            return;
        }
        if (verifiedApk != null && verifiedApk.isFile()) openInstaller();
        else downloadApk(release);
    }

    private void continueAfterPermission() {
        if (!resumed || !awaitingPermission || release == null) return;
        awaitingPermission = false;
        if (getPackageManager().canRequestPackageInstalls()) update();
        else show(R.string.update_need_permission, R.string.update_action, this::update);
    }

    private void downloadApk(GitHubUpdates.Release selected) {
        show(R.string.update_downloading, 0, null);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            try {
                File apk = GitHubUpdates.download(getApplicationContext(), selected, installed, percent -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.setProgress(percent);
                    status.setText(percent == 100 ? getString(R.string.update_verifying) : getString(R.string.update_download_progress, percent));
                }));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        apk.delete();
                        return;
                    }
                    verifiedApk = apk;
                    show(getString(R.string.update_available, selected.version()), R.string.update_install_action, this::update);
                    if (resumed) openInstaller();
                });
            } catch (Exception e) {
                Log.w(TAG, "Update download or verification failed", e);
                int reason = e instanceof GitHubUpdates.VerificationException verification ? verification.reason : R.string.update_download_failed;
                boolean retry = !(e instanceof GitHubUpdates.VerificationException);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) show(reason, retry ? R.string.update_retry : 0, this::update);
                });
            }
        });
    }

    private void openInstaller() {
        try {
            Uri apk = FileProvider.getUriForFile(this, getPackageName() + ".updates", verifiedApk);
            Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Cannot open package installer", e);
            show(R.string.update_install_failed, R.string.update_install_action, this::update);
        }
    }

    private void show(int message, int button, Runnable click) { show(getString(message), button, click); }

    private void show(String message, int button, Runnable click) {
        status.setText(message);
        progress.setVisibility(View.GONE);
        action.setVisibility(button == 0 ? View.GONE : View.VISIBLE);
        if (button != 0) {
            action.setText(button);
            action.setOnClickListener(v -> click.run());
        }
    }
}

package ru.spark108.sipphoneservice;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class AccountActivity extends Activity {
    private Config config;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        config = Config.load(this);
        setContentView(R.layout.activity_account);
        SettingsUi.header(this, R.string.account_title);
        setupRow(R.id.user_row, "Имя пользователя", () -> edit("Имя пользователя", config.user, false, value -> config.user = value));
        String displayNameTitle = getString(R.string.display_name_title);
        setupRow(R.id.display_name_row, displayNameTitle, () -> edit(displayNameTitle, config.displayName,
            false, value -> config.displayName = value, this::updatePhoneAccount));
        setupRow(R.id.domain_row, "Домен SIP", () -> edit("Домен SIP", config.domain, false, value -> config.domain = value));
        setupRow(R.id.server_row, "Прокси SIP", () -> edit("Прокси SIP", config.server, false, value -> config.server = value));
        setupRow(R.id.password_row, "Пароль", () -> edit("Пароль", config.password, true, value -> config.password = value));
        updateRows();
    }

    private void setupRow(int id, String title, Runnable click) {
        View row = findViewById(id);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        row.setOnClickListener(v -> click.run());
    }

    private void updateRows() {
        summary(R.id.user_row, config.user.isEmpty() ? "Не указано" : config.user);
        summary(R.id.display_name_row, config.displayName.isEmpty() ? getString(R.string.display_name_default) : config.displayName);
        summary(R.id.domain_row, config.domain.isEmpty() ? "Не указан" : config.domain);
        summary(R.id.server_row, config.server.isEmpty() ? "По умолчанию — домен SIP" : config.server);
        summary(R.id.password_row, config.password.isEmpty() ? "Не указан" : "Установлен");
    }
    private void summary(int id, String value) {
        TextView view = findViewById(id).findViewById(R.id.row_summary);
        view.setText(value);
        view.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private interface Change { void apply(String value); }
    private void edit(String title, String oldValue, boolean secret, Change change) {
        edit(title, oldValue, secret, change, this::connectionSettingsSaved);
    }

    private void edit(String title, String oldValue, boolean secret, Change change, Runnable afterSave) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        input.setText(oldValue);
        input.selectAll();
        FrameLayout holder = new FrameLayout(this);
        int margin = (int) (24 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2);
        params.setMargins(margin, 0, margin, 0);
        holder.addView(input, params);
        new AlertDialog.Builder(this).setTitle(title).setView(holder)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", (dialog, which) -> {
                String value = input.getText().toString();
                if (!secret) value = value.trim();
                if (value.equals(oldValue)) return;
                change.apply(value);
                try {
                    config.save(this);
                } catch (Exception e) {
                    config = Config.load(this);
                    Toast.makeText(this, "Не удалось сохранить параметр", Toast.LENGTH_LONG).show();
                    return;
                }
                updateRows();
                afterSave.run();
            }).show();
    }

    private void connectionSettingsSaved() {
        SipService service = SipService.instance();
        if (service != null) service.reloadConfig();
        else updatePhoneAccount();
    }

    private void updatePhoneAccount() {
        SipConnectionService.register(this, config);
    }
}

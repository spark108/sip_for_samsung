package ru.spark108.sipphoneservice;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;

final class Config {
    String user = "", domain = "", server = "", password = "";
    private static final String KEY = "sip_password_v1";

    static Config load(Context context) {
        SharedPreferences p = context.getSharedPreferences("sip", 0);
        Config c = new Config();
        c.user = p.getString("user", "");
        c.domain = p.getString("domain", "");
        c.server = p.getString("server", "");
        String saved = p.getString("password", "");
        // Preserve the account saved by the later multi-account UI when the
        // original single-account engine is restored.
        if (!p.contains("user")) {
            try {
                JSONArray ids = new JSONArray(p.getString("ids", "[]"));
                String id = "sip-account";
                boolean found = false;
                for (int i = 0; i < ids.length(); i++) if (id.equals(ids.getString(i))) found = true;
                if (!found && ids.length() > 0) id = ids.getString(0);
                String prefix = "account." + id + ".";
                c.user = p.getString(prefix + "user", "");
                c.domain = p.getString(prefix + "domain", "");
                c.server = p.getString(prefix + "server", "");
                saved = p.getString(prefix + "password", "");
            } catch (Exception ignored) { }
        }
        try {
            if (!saved.isEmpty()) {
                byte[] data = Base64.decode(saved, Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, data, 0, 12));
                c.password = new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) { c.password = ""; }
        return c;
    }

    void save(Context context) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        byte[] data = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, data, 0, iv.length);
        System.arraycopy(encrypted, 0, data, iv.length, encrypted.length);
        context.getSharedPreferences("sip", 0).edit()
            .putString("user", user).putString("domain", domain).putString("server", server)
            .putString("password", Base64.encodeToString(data, Base64.NO_WRAP)).apply();
    }

    boolean valid() { return !user.isEmpty() && !domain.isEmpty() && !password.isEmpty(); }

    private static SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY, null);
    }
}

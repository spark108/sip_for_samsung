package ru.spark108.sipphoneservice;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.function.IntConsumer;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class GitHubUpdates {
    private static final String REPOSITORY = "/spark108/sip_for_samsung";
    private static final String LATEST = "https://api.github.com/repos" + REPOSITORY + "/releases/latest";
    private static final String APK_NAME = "SIP.apk";
    private static final long MAX_APK_BYTES = 256L * 1024 * 1024;
    private static final long APK_CACHE_MILLIS = 24L * 60 * 60 * 1000;
    private static final List<String> DOWNLOAD_HOSTS = Arrays.asList(
        "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
        "github-releases.githubusercontent.com");

    enum Status { NO_RELEASE, CURRENT, MISSING_APK, AVAILABLE }

    record Release(String version, URL download, long size, String sha256) { }
    record Check(Status status, String version, Release release) { }

    static final class VerificationException extends IOException {
        final int reason;
        VerificationException(int reason) { this.reason = reason; }
    }

    private GitHubUpdates() { }

    static Check check(String installedVersion) throws IOException, JSONException {
        HttpsURLConnection connection = open(new URL(LATEST));
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return new Check(Status.NO_RELEASE, null, null);
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("GitHub Releases HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                return parseRelease(new JSONObject(readMetadata(input)), installedVersion);
            }
        } finally {
            connection.disconnect();
        }
    }

    static Check parseRelease(JSONObject data, String installedVersion) throws IOException, JSONException {
        if (data.optBoolean("draft") || data.optBoolean("prerelease")) {
            return new Check(Status.NO_RELEASE, null, null);
        }
        String tag = data.getString("tag_name");
        String version = versionNumber(tag);
        if (compareVersions(version, installedVersion) <= 0) return new Check(Status.CURRENT, version, null);
        JSONArray assets = data.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!APK_NAME.equals(asset.optString("name")) || !"uploaded".equals(asset.optString("state"))) continue;
            URL download = new URL(asset.getString("browser_download_url"));
            long size = asset.optLong("size", -1);
            String digest = asset.optString("digest").toLowerCase(Locale.ROOT);
            if (!allowedDownload(download) || !"github.com".equals(download.getHost())
                    || !(REPOSITORY + "/releases/download/" + tag + "/" + APK_NAME).equals(download.getPath())
                    || !digest.matches("sha256:[0-9a-f]{64}") || size <= 0 || size > MAX_APK_BYTES) break;
            return new Check(Status.AVAILABLE, version, new Release(version, download, size, digest.substring(7)));
        }
        return new Check(Status.MISSING_APK, version, null);
    }

    private static String readMetadata(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (output.size() + length > 1024 * 1024) throw new IOException("Release metadata is too large");
            output.write(buffer, 0, length);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String versionNumber(String version) {
        if (version == null || !version.matches("[vV]?[0-9]{1,9}(\\.[0-9]{1,9}){1,3}")) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
        return version.charAt(0) == 'v' || version.charAt(0) == 'V' ? version.substring(1) : version;
    }

    static int compareVersions(String left, String right) {
        String[] first = versionNumber(left).split("\\.");
        String[] second = versionNumber(right).split("\\.");
        for (int i = 0; i < Math.max(first.length, second.length); i++) {
            int a = i < first.length ? Integer.parseInt(first[i]) : 0;
            int b = i < second.length ? Integer.parseInt(second[i]) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    static File download(Context context, Release release, PackageInfo installed, IntConsumer onProgress) throws Exception {
        File folder = new File(context.getCacheDir(), "updates");
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) throw new IOException("Cannot open APK cache");
        File[] expired = folder.listFiles(file -> file.isFile() && System.currentTimeMillis() - file.lastModified() > APK_CACHE_MILLIS);
        if (expired != null) for (File file : expired) file.delete();

        File apk = File.createTempFile("sip-", ".apk", folder);
        boolean verified = false;
        try {
            transfer(release, apk, onProgress);
            onProgress.accept(100);
            verifyApk(context.getPackageManager(), apk, release, installed);
            verified = true;
            return apk;
        } finally {
            if (!verified) apk.delete();
        }
    }

    private static void transfer(Release release, File apk, IntConsumer onProgress) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpsURLConnection connection = openDownload(release.download());
        long received = 0;
        int progress = 0;
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(apk)) {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                received += length;
                if (received > release.size()) throw new VerificationException(R.string.update_bad_apk);
                output.write(buffer, 0, length);
                digest.update(buffer, 0, length);
                int percent = (int) (100L * received / release.size());
                if (percent > progress && percent < 100) {
                    progress = percent;
                    onProgress.accept(percent);
                }
            }
        } finally {
            connection.disconnect();
        }
        StringBuilder hash = new StringBuilder(64);
        for (byte value : digest.digest()) hash.append(Character.forDigit((value & 255) >>> 4, 16)).append(Character.forDigit(value & 15, 16));
        if (received != release.size() || !release.sha256().contentEquals(hash)) throw new VerificationException(R.string.update_bad_apk);
    }

    private static void verifyApk(PackageManager manager, File file, Release release, PackageInfo installed) throws VerificationException {
        PackageInfo candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || !installed.packageName.equals(candidate.packageName)
                || candidate.getLongVersionCode() <= installed.getLongVersionCode()) {
            throw new VerificationException(R.string.update_bad_apk);
        }
        try {
            if (compareVersions(candidate.versionName, release.version()) != 0) throw new VerificationException(R.string.update_bad_apk);
        } catch (IllegalArgumentException e) {
            throw new VerificationException(R.string.update_bad_apk);
        }
        SigningInfo currentSigner = installed.signingInfo;
        SigningInfo newSigner = candidate.signingInfo;
        if (currentSigner == null || newSigner == null || currentSigner.getApkContentsSigners().length == 0
                || !new HashSet<>(Arrays.asList(currentSigner.getApkContentsSigners()))
                    .equals(new HashSet<>(Arrays.asList(newSigner.getApkContentsSigners())))) {
            throw new VerificationException(R.string.update_bad_signature);
        }
    }

    private static boolean allowedDownload(URL url) {
        return "https".equals(url.getProtocol()) && DOWNLOAD_HOSTS.contains(url.getHost())
            && (url.getPort() == -1 || url.getPort() == 443) && url.getUserInfo() == null;
    }

    private static HttpsURLConnection open(URL url) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "SIP Android updater");
        return connection;
    }

    private static HttpsURLConnection openDownload(URL url) throws IOException {
        for (int i = 0; i < 5; i++) {
            if (!allowedDownload(url)) throw new IOException("APK download address is not on GitHub");
            HttpsURLConnection connection = open(url);
            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_OK) return connection;
                String location = connection.getHeaderField("Location");
                if (status < 300 || status >= 400 || location == null) throw new IOException("APK download HTTP " + status);
                url = new URL(url, location);
            } catch (IOException | RuntimeException e) {
                connection.disconnect();
                throw e;
            }
            connection.disconnect();
        }
        throw new IOException("Too many APK redirects");
    }
}

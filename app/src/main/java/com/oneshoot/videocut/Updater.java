package com.oneshoot.videocut;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * التحديث من داخل التطبيق: بيقرأ latest.json من آخر Release، ولو فيه نسخة أحدث
 * بينزّل الـ APK ويفتح شاشة التثبيت. لازم الـ Release يكون public عشان الرابط يتقري من غير توكن.
 */
public final class Updater {

    public static final String UPD_URL =
            "https://github.com/oneshoot5010/video-cut/releases/latest/download/latest.json";

    private static final String APK_NAME = "update.apk";

    public static final class Info {
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;

        Info(int versionCode, String versionName, String apkUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
        }
    }

    private Updater() { }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(60000);
        con.setInstanceFollowRedirects(true);
        return con;
    }

    public static Info fetchInfo() throws Exception {
        HttpURLConnection con = open(UPD_URL);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = con.getInputStream()) {
            byte[] b = new byte[4096];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
        } finally {
            con.disconnect();
        }
        JSONObject j = new JSONObject(bo.toString("UTF-8"));
        return new Info(j.getInt("versionCode"), j.getString("versionName"), j.getString("url"));
    }

    public static boolean isNewer(Context c, Info info) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return info.versionCode > pi.versionCode;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static File download(Context c, Info info) throws Exception {
        File dir = new File(c.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, APK_NAME);
        HttpURLConnection con = open(info.apkUrl);
        try (InputStream in = con.getInputStream();
             OutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[64 * 1024];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
        } finally {
            con.disconnect();
        }
        return out;
    }

    // أندرويد 8+ بيطلب إذن منفصل "تثبيت تطبيقات غير معروفة" للتطبيق اللي بيثبّت
    public static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    public static Intent unknownSourcesIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName()));
    }

    public static void install(Context c, File apk) {
        Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}

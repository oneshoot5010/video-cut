package com.oneshoot.videocut;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;

import java.io.File;

@OptIn(markerClass = UnstableApi.class)
public class SettingsActivity extends AppCompatActivity {

    private TextView versionText, statusText;
    private Button checkBtn, installBtn;
    private Updater.Info found;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        versionText = findViewById(R.id.versionText);
        statusText = findViewById(R.id.updStatus);
        checkBtn = findViewById(R.id.checkBtn);
        installBtn = findViewById(R.id.installBtn);

        String vn = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            vn = pi.versionName;
        } catch (Exception ignored) { }
        versionText.setText(getString(R.string.current_version, vn));

        checkBtn.setOnClickListener(v -> check());
        installBtn.setOnClickListener(v -> install());
    }

    private void setLoading(boolean loading) {
        checkBtn.setEnabled(!loading);
        installBtn.setEnabled(!loading);
    }

    private void check() {
        setLoading(true);
        statusText.setText(R.string.upd_checking);
        new Thread(() -> {
            Updater.Info info = null;
            String err = null;
            try {
                info = Updater.fetchInfo();
            } catch (Exception ex) {
                err = ex.getMessage();
            }
            final Updater.Info fi = info;
            final String fe = err;
            runOnUiThread(() -> {
                setLoading(false);
                if (fe != null) {
                    statusText.setText(getString(R.string.upd_error, fe));
                    return;
                }
                if (Updater.isNewer(this, fi)) {
                    found = fi;
                    statusText.setText(getString(R.string.upd_found, fi.versionName));
                    installBtn.setVisibility(View.VISIBLE);
                } else {
                    found = null;
                    statusText.setText(R.string.upd_latest);
                    installBtn.setVisibility(View.GONE);
                }
            });
        }).start();
    }

    private void install() {
        if (found == null) return;
        if (!Updater.canInstall(this)) {
            statusText.setText(R.string.upd_allow_source);
            startActivity(Updater.unknownSourcesIntent(this));
            return;
        }
        setLoading(true);
        statusText.setText(R.string.upd_downloading);
        final Updater.Info info = found;
        new Thread(() -> {
            File apk = null;
            String err = null;
            try {
                apk = Updater.download(this, info);
            } catch (Exception ex) {
                err = ex.getMessage();
            }
            final File fa = apk;
            final String fe = err;
            runOnUiThread(() -> {
                setLoading(false);
                if (fe != null || fa == null) {
                    statusText.setText(getString(R.string.upd_error, fe));
                    return;
                }
                statusText.setText(R.string.upd_opening);
                Updater.install(this, fa);
            });
        }).start();
    }
}

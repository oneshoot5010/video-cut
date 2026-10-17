package com.oneshoot.videocut;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.ui.PlayerView;

import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity {

    private static final int STEP_MS = 100;   // كل نقطة في السلايدر = 100ms
    private static final int MIN_GAP = 10;    // أقل مدة للقص = ثانية
    private static final long MAX_AUDIO_BYTES = 24L * 1024 * 1024;  // حد OpenAI 25MB

    private static final int[] COLORS = {
            0xFFFFFFFF, 0xFFFFEB3B, 0xFFFF5252, 0xFF69F0AE, 0xFF40C4FF, 0xFF000000
    };

    private static final float[] SPEEDS = {0.5f, 1f, 1.5f, 2f};
    private static final int SPEED_NORMAL = 1;   // موضع 1x في السبينر

    // {اسم الملف في assets/fonts, الاسم المعروض}
    private static final String[][] ASSET_FONTS = {
            {"cairo", "Cairo"}, {"tajawal", "Tajawal"}, {"amiri", "Amiri"},
            {"lalezar", "Lalezar"}, {"arefruqaa", "Aref Ruqaa"}, {"reemkufi", "Reem Kufi"},
            {"elmessiri", "El Messiri"}, {"changa", "Changa"}
    };

    private final List<String> fontNames = new ArrayList<>();
    private final List<Typeface> fontFaces = new ArrayList<>();
    private final TextRender.Params params = new TextRender.Params();

    // الكابشن بالوقت المطلق (على تايم لاين الفيديو الأصلي)
    private List<Caption> captions = new ArrayList<>();

    private ExoPlayer player;
    private PlayerView playerView;
    private TextOverlayView overlay;
    private SeekBar startBar, endBar, sizeBar;
    private TextView startText, endText, status, musicText, planText;
    private Button pickBtn, playBtn, exportBtn, transcribeBtn, musicBtn, clearMusicBtn, proBtn, settingsBtn;
    private EditText textInput, apiKeyInput, captionsInput;
    private Spinner fontSpinner, speedSpinner, filterSpinner;
    private LinearLayout colorRow;
    private CheckBox strokeCheck, muteCheck;
    private ProgressBar progress;

    private Uri videoUri;
    private Uri musicUri;
    private long durationMs = 0;
    private int frameW = 0, frameH = 0;
    private Transformer transformer;
    private Transformer audioTransformer;
    private File outFile;
    private boolean exporting = false;
    private boolean transcribing = false;
    private boolean audioStage = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ProgressHolder holder = new ProgressHolder();

    private final ActivityResultLauncher<String> picker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) loadVideo(uri);
            });

    private final ActivityResultLauncher<String[]> musicPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                    // مش كل المصادر بتدعم الصلاحية الدائمة، والصلاحية المؤقتة تكفي
                }
                musicUri = uri;
                muteCheck.setChecked(true);   // عشان الموسيقى متتسمعش فوق صوت الفيديو
                refreshMusicLabel();
            });

    private final Runnable progressPoller = new Runnable() {
        @Override
        public void run() {
            if (!exporting || transformer == null) return;
            int state = transformer.getProgress(holder);
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                progress.setProgress(holder.progress);
            }
            handler.postDelayed(this, 300);
        }
    };

    // يوقف المعاينة عند نهاية القص، ويحدّث الكابشن الظاهر حسب وقت التشغيل
    private final Runnable previewTick = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                if (durationMs > 0 && player.isPlaying()) {
                    long end = endBar.getProgress() * (long) STEP_MS;
                    if (player.getCurrentPosition() >= end) {
                        player.pause();
                        player.seekTo(startBar.getProgress() * (long) STEP_MS);
                    }
                }
                overlay.setCaption(captions.isEmpty() ? "" : captionAt(player.getCurrentPosition()));
            }
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.playerView);
        overlay = findViewById(R.id.overlay);
        startBar = findViewById(R.id.startBar);
        endBar = findViewById(R.id.endBar);
        sizeBar = findViewById(R.id.sizeBar);
        startText = findViewById(R.id.startText);
        endText = findViewById(R.id.endText);
        status = findViewById(R.id.status);
        pickBtn = findViewById(R.id.pickBtn);
        playBtn = findViewById(R.id.playBtn);
        exportBtn = findViewById(R.id.exportBtn);
        transcribeBtn = findViewById(R.id.transcribeBtn);
        textInput = findViewById(R.id.textInput);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        captionsInput = findViewById(R.id.captionsInput);
        fontSpinner = findViewById(R.id.fontSpinner);
        colorRow = findViewById(R.id.colorRow);
        strokeCheck = findViewById(R.id.strokeCheck);
        progress = findViewById(R.id.progress);
        speedSpinner = findViewById(R.id.speedSpinner);
        filterSpinner = findViewById(R.id.filterSpinner);
        musicBtn = findViewById(R.id.musicBtn);
        clearMusicBtn = findViewById(R.id.clearMusicBtn);
        musicText = findViewById(R.id.musicText);
        muteCheck = findViewById(R.id.muteCheck);
        planText = findViewById(R.id.planText);
        proBtn = findViewById(R.id.proBtn);
        settingsBtn = findViewById(R.id.settingsBtn);

        apiKeyInput.setText(getSharedPreferences("vc", MODE_PRIVATE).getString("key", ""));

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && durationMs == 0) {
                    long d = player.getDuration();
                    if (d != C.TIME_UNSET && d > 0) onDurationKnown(d);
                }
            }
        });

        startText.setText(getString(R.string.start_label, fmt(0)));
        endText.setText(getString(R.string.end_label, fmt(0)));

        pickBtn.setOnClickListener(v -> picker.launch("video/*"));
        exportBtn.setOnClickListener(v -> startExport());
        playBtn.setOnClickListener(v -> togglePlay());
        transcribeBtn.setOnClickListener(v -> startTranscribe());
        musicBtn.setOnClickListener(v -> musicPicker.launch(new String[]{"audio/*"}));
        clearMusicBtn.setOnClickListener(v -> {
            musicUri = null;
            refreshMusicLabel();
        });
        proBtn.setOnClickListener(v ->
                Toast.makeText(this, R.string.pro_soon, Toast.LENGTH_SHORT).show());

        settingsBtn.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, SettingsActivity.class)));

        setupTextControls();
        setupExportControls();
        refreshMusicLabel();
        refreshPlan();

        captionsInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                captions = parseCaptions(s.toString());
            }
        });

        startBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                if (!fromUser) return;
                int limit = Math.max(0, endBar.getProgress() - MIN_GAP);
                if (p > limit) {
                    p = limit;
                    bar.setProgress(p);
                }
                startText.setText(getString(R.string.start_label, fmt(p * (long) STEP_MS)));
                player.seekTo(p * (long) STEP_MS);
            }
        });

        endBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                if (!fromUser) return;
                int limit = Math.min(bar.getMax(), startBar.getProgress() + MIN_GAP);
                if (p < limit) {
                    p = limit;
                    bar.setProgress(p);
                }
                endText.setText(getString(R.string.end_label, fmt(p * (long) STEP_MS)));
                player.seekTo(p * (long) STEP_MS);
            }
        });

        handler.post(previewTick);
    }

    // ---------- السرعة والفلاتر ----------

    private void setupExportControls() {
        speedSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.speeds)));
        speedSpinner.setSelection(SPEED_NORMAL);
        speedSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                // المعاينة بتتشغل بنفس السرعة اللي هتتصدّر بيها
                if (player != null) player.setPlaybackParameters(new PlaybackParameters(SPEEDS[pos]));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        filterSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.filters)));
    }

    private float currentSpeed() {
        return SPEEDS[speedSpinner.getSelectedItemPosition()];
    }

    private static SpeedProvider constantSpeed(final float speed) {
        return new SpeedProvider() {
            @Override
            public float getSpeed(long timeUs) {
                return speed;
            }

            @Override
            public long getNextSpeedChangeTimeUs(long timeUs) {
                return C.TIME_UNSET;
            }
        };
    }

    // الفلتر حسب ترتيب الـ spinner: 0 عادي، 1 دافي، 2 بارد، 3 أبيض وأسود، 4 تباين، 5 سطوع
    private static Effect filterEffect(int pos) {
        switch (pos) {
            case 1:
                return new RgbAdjustment.Builder().setRedScale(1.12f).setBlueScale(0.9f).build();
            case 2:
                return new RgbAdjustment.Builder().setRedScale(0.9f).setBlueScale(1.12f).build();
            case 3:
                return new HslAdjustment.Builder().adjustSaturation(-100f).build();
            case 4:
                return new Contrast(0.3f);
            case 5:
                return new Brightness(0.2f);
            default:
                return null;
        }
    }

    private void refreshMusicLabel() {
        musicText.setText(musicUri == null ? R.string.music_none : R.string.music_set);
    }

    // ---------- الخطة المجانية ----------

    private void refreshPlan() {
        if (Plan.isPro(this)) {
            planText.setText(R.string.plan_pro);
        } else {
            planText.setText(getString(R.string.plan_free,
                    Plan.remainingToday(this), Plan.FREE_DAILY_LIMIT));
        }
    }

    // علامة VideoCut في الركن، بتتحط على الفيديو بس في النسخة المجانية
    private Bitmap watermarkBitmap() {
        Bitmap bmp = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(frameH * 0.035f);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setColor(0xCCFFFFFF);
        p.setShadowLayer(frameH * 0.005f, 0, 0, 0xCC000000);
        c.drawText("VideoCut", frameW * 0.96f, frameH * 0.96f, p);
        return bmp;
    }

    // ---------- النص والخطوط ----------

    private void setupTextControls() {
        buildFonts();
        params.typeface = fontFaces.get(0);
        overlay.setParams(params);

        fontSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, fontNames));
        fontSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                params.typeface = fontFaces.get(pos);
                overlay.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        textInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                params.text = s.toString();
                overlay.invalidate();
            }
        });

        params.sizeFrac = 0.03f + sizeBar.getProgress() * 0.0012f;
        sizeBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                params.sizeFrac = 0.03f + p * 0.0012f;
                overlay.invalidate();
            }
        });

        strokeCheck.setOnCheckedChangeListener((b, checked) -> {
            params.stroke = checked;
            overlay.invalidate();
        });

        buildColorRow();
    }

    private void buildFonts() {
        addFont("نظام - عادي", Typeface.create("sans-serif", Typeface.NORMAL));
        addFont("نظام - عريض", Typeface.create("sans-serif", Typeface.BOLD));
        addFont("نظام - نسخ", Typeface.create("serif", Typeface.BOLD));

        for (String[] f : ASSET_FONTS) {
            try {
                String path = "fonts/" + f[0] + ".ttf";
                getAssets().open(path).close();   // يتأكد إن الملف موجود
                Typeface tf = new Typeface.Builder(getAssets(), path)
                        .setFontVariationSettings("'wght' 700")
                        .build();
                if (tf == null) tf = Typeface.createFromAsset(getAssets(), path);
                addFont(f[1], tf);
            } catch (Exception ignored) {
                // الخط مش موجود أو بايظ: نتخطاه
            }
        }
    }

    private void addFont(String name, Typeface tf) {
        fontNames.add(name);
        fontFaces.add(tf);
    }

    private void buildColorRow() {
        float density = getResources().getDisplayMetrics().density;
        int size = (int) (40 * density);
        int margin = (int) (6 * density);
        for (int color : COLORS) {
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, margin, margin, margin);
            v.setLayoutParams(lp);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            d.setStroke((int) (2 * density), 0xFF888888);
            v.setBackground(d);
            v.setOnClickListener(x -> {
                params.color = color;
                overlay.invalidate();
            });
            colorRow.addView(v);
        }
    }

    // ---------- الكابشن ----------

    private static List<Caption> parseCaptions(String raw) {
        List<Caption> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String[] p = line.split("\\|", 3);
            if (p.length < 3) continue;
            try {
                double s = Double.parseDouble(p[0].trim());
                double e = Double.parseDouble(p[1].trim());
                String t = p[2].trim();
                if (t.isEmpty() || e <= s) continue;
                out.add(new Caption((long) (s * 1000), (long) (e * 1000), t));
            } catch (NumberFormatException ignored) {
                // سطر مش مظبوط: نتخطاه
            }
        }
        return out;
    }

    private String captionAt(long ms) {
        for (Caption c : captions) {
            if (ms >= c.startMs && ms < c.endMs) return c.text;
        }
        return "";
    }

    private void startTranscribe() {
        if (videoUri == null || durationMs <= 0 || exporting || transcribing) return;

        String key = apiKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            status.setText(R.string.status_no_key);
            return;
        }
        getSharedPreferences("vc", MODE_PRIVATE).edit().putString("key", key).apply();

        final long s = startBar.getProgress() * (long) STEP_MS;
        long e = endBar.getProgress() * (long) STEP_MS;
        if (endBar.getProgress() >= endBar.getMax()) e = durationMs;
        e = Math.min(e, durationMs);

        player.pause();

        EditedMediaItem audioItem = new EditedMediaItem.Builder(clipItem(s, e))
                .setRemoveVideo(true)
                .build();
        final File audio = new File(getCacheDir(), "aud_" + System.currentTimeMillis() + ".m4a");

        transcribing = true;
        audioStage = true;
        setBusy(true);
        progress.setIndeterminate(true);
        status.setText(R.string.status_audio);

        audioTransformer = new Transformer.Builder(this)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        audioStage = false;
                        uploadAudio(audio, key, s);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result,
                                        ExportException exception) {
                        audioStage = false;
                        transcribing = false;
                        setBusy(false);
                        //noinspection ResultOfMethodCallIgnored
                        audio.delete();
                        status.setText(getString(R.string.status_error, exception.getMessage()));
                    }
                })
                .build();
        audioTransformer.start(audioItem, audio.getAbsolutePath());
    }

    private void uploadAudio(File audio, String key, long baseMs) {
        status.setText(R.string.status_transcribing);
        new Thread(() -> {
            List<Caption> result = null;
            String err = null;
            try {
                if (audio.length() > MAX_AUDIO_BYTES) {
                    throw new IllegalStateException(getString(R.string.status_too_long));
                }
                result = transcribe(audio, key, baseMs);
            } catch (Exception ex) {
                err = ex.getMessage();
            } finally {
                //noinspection ResultOfMethodCallIgnored
                audio.delete();
            }
            final List<Caption> caps = result;
            final String error = err;
            runOnUiThread(() -> {
                transcribing = false;
                setBusy(false);
                if (caps != null) {
                    StringBuilder sb = new StringBuilder();
                    for (Caption c : caps) {
                        sb.append(String.format(Locale.US, "%.1f|%.1f|%s\n",
                                c.startMs / 1000.0, c.endMs / 1000.0, c.text));
                    }
                    captionsInput.setText(sb.toString());   // الـ TextWatcher بيحدّث القائمة
                    status.setText(getString(R.string.status_caps_done, caps.size()));
                } else {
                    status.setText(getString(R.string.status_error, error));
                }
            });
        }).start();
    }

    private List<Caption> transcribe(File audio, String key, long baseMs) throws Exception {
        String b = "----VideoCut" + System.currentTimeMillis();
        String nl = "\r\n";
        StringBuilder head = new StringBuilder();
        appendField(head, b, "model", "whisper-1");
        appendField(head, b, "response_format", "verbose_json");
        appendField(head, b, "language", "ar");
        head.append("--").append(b).append(nl)
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"").append(nl)
                .append("Content-Type: audio/mp4").append(nl).append(nl);
        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = (nl + "--" + b + "--" + nl).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.openai.com/v1/audio/transcriptions").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(30000);
            c.setReadTimeout(300000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
            c.setFixedLengthStreamingMode(headBytes.length + audio.length() + tailBytes.length);

            try (OutputStream out = c.getOutputStream();
                 InputStream in = new FileInputStream(audio)) {
                out.write(headBytes);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.write(tailBytes);
            }

            int code = c.getResponseCode();
            String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code != 200) throw new IllegalStateException("HTTP " + code + ": " + apiError(body));

            List<Caption> caps = new ArrayList<>();
            JSONArray segs = new JSONObject(body).optJSONArray("segments");
            if (segs != null) {
                for (int i = 0; i < segs.length(); i++) {
                    JSONObject sg = segs.getJSONObject(i);
                    String text = sg.optString("text", "").trim();
                    if (text.isEmpty()) continue;
                    long st = baseMs + (long) (sg.getDouble("start") * 1000);
                    long en = baseMs + (long) (sg.getDouble("end") * 1000);
                    if (en <= st) en = st + 500;
                    caps.add(new Caption(st, en, text));
                }
            }
            return caps;
        } finally {
            c.disconnect();
        }
    }

    private static void appendField(StringBuilder sb, String boundary, String name, String value) {
        sb.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n");
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    private static String apiError(String body) {
        try {
            return new JSONObject(body).getJSONObject("error").getString("message");
        } catch (Exception ignored) {
            return body.length() > 150 ? body.substring(0, 150) : body;
        }
    }

    // ---------- الفيديو ----------

    private MediaItem clipItem(long startMs, long endMs) {
        return new MediaItem.Builder()
                .setUri(videoUri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build())
                .build();
    }

    private void togglePlay() {
        if (durationMs <= 0) return;
        if (player.isPlaying()) {
            player.pause();
            return;
        }
        long start = startBar.getProgress() * (long) STEP_MS;
        long end = endBar.getProgress() * (long) STEP_MS;
        long pos = player.getCurrentPosition();
        if (pos < start || pos >= end) player.seekTo(start);
        player.play();
    }

    private void loadVideo(Uri uri) {
        videoUri = uri;
        durationMs = 0;
        exportBtn.setEnabled(false);
        transcribeBtn.setEnabled(false);
        startBar.setEnabled(false);
        endBar.setEnabled(false);
        progress.setVisibility(ProgressBar.INVISIBLE);
        captionsInput.setText("");
        readFrameSize(uri);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
    }

    // أبعاد الإطار بعد تطبيق الدوران (زي ما بيظهر وبيتصدّر)
    private void readFrameSize(Uri uri) {
        frameW = 0;
        frameH = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rotStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rot = rotStr == null ? 0 : Integer.parseInt(rotStr);
            if (rot == 90 || rot == 270) {
                int t = w;
                w = h;
                h = t;
            }
            frameW = w;
            frameH = h;
        } catch (Exception ignored) {
            frameW = 0;
            frameH = 0;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) { }
        }
        overlay.setFrame(frameW, frameH);
    }

    private void onDurationKnown(long d) {
        durationMs = d;
        int max = (int) (d / STEP_MS);
        startBar.setMax(max);
        endBar.setMax(max);
        startBar.setProgress(0);
        endBar.setProgress(max);
        startBar.setEnabled(true);
        endBar.setEnabled(true);
        exportBtn.setEnabled(true);
        transcribeBtn.setEnabled(true);
        startText.setText(getString(R.string.start_label, fmt(0)));
        endText.setText(getString(R.string.end_label, fmt(d)));
        status.setText(R.string.status_loaded);
    }

    // ---------- التصدير ----------

    private void startExport() {
        if (videoUri == null || durationMs <= 0 || exporting || transcribing) return;

        if (!Plan.isPro(this) && Plan.remainingToday(this) <= 0) {
            status.setText(R.string.status_limit);
            return;
        }

        long s = startBar.getProgress() * (long) STEP_MS;
        long e = endBar.getProgress() * (long) STEP_MS;
        if (endBar.getProgress() >= endBar.getMax()) e = durationMs;
        e = Math.min(e, durationMs);

        final float speed = currentSpeed();
        final boolean mute = muteCheck.isChecked();
        final boolean watermark = !Plan.isPro(this);
        long clipLen = e - s;
        long outLen = Math.round(clipLen / (double) speed);   // طول المقطع بعد تغيير السرعة

        // الكابشن نسبي لبداية المقطع المقصوص، ومتحسب على الوقت الجديد بعد السرعة
        List<Caption> clipCaps = new ArrayList<>();
        for (Caption c : captions) {
            long cs = Math.round((c.startMs - s) / (double) speed);
            long ce = Math.round((c.endMs - s) / (double) speed);
            if (ce <= 0 || cs >= outLen) continue;
            clipCaps.add(new Caption(Math.max(0, cs), Math.min(outLen, ce), c.text));
        }

        boolean hasText = params.text != null && !params.text.trim().isEmpty();
        boolean hasCaps = !clipCaps.isEmpty();
        if ((hasText || hasCaps || watermark) && (frameW <= 0 || frameH <= 0)) {
            status.setText(R.string.status_no_size);
            return;
        }

        player.pause();

        List<AudioProcessor> audioFx = new ArrayList<>();
        List<Effect> videoFx = new ArrayList<>();

        if (speed != 1f) {
            if (mute) {
                // مفيش صوت أصلي، فالسرعة على الفيديو بس
                videoFx.add(new SpeedChangeEffect(speed));
            } else {
                // السرعة على الصوت والفيديو مع بعض عشان يفضلوا متزامنين
                Pair<AudioProcessor, Effect> sp =
                        Effects.createExperimentalSpeedChangingEffect(constantSpeed(speed));
                audioFx.add(sp.first);
                videoFx.add(sp.second);
            }
        }

        Effect filter = filterEffect(filterSpinner.getSelectedItemPosition());
        if (filter != null) videoFx.add(filter);

        ImmutableList.Builder<TextureOverlay> overlays = ImmutableList.builder();
        if (hasText) {
            overlays.add(BitmapOverlay.createStaticBitmapOverlay(TextRender.bitmap(frameW, frameH, params)));
        }
        if (watermark) {
            overlays.add(BitmapOverlay.createStaticBitmapOverlay(watermarkBitmap()));
        }
        if (hasCaps) {
            overlays.add(new CaptionOverlay(frameW, frameH,
                    TextRender.captionStyle(params, ""), clipCaps));
        }
        ImmutableList<TextureOverlay> ol = overlays.build();
        if (!ol.isEmpty()) videoFx.add(new OverlayEffect(ol));

        EditedMediaItem.Builder vb = new EditedMediaItem.Builder(clipItem(s, e))
                .setRemoveAudio(mute);
        if (!audioFx.isEmpty() || !videoFx.isEmpty()) {
            vb.setEffects(new Effects(audioFx, videoFx));
        }

        List<EditedMediaItemSequence> sequences = new ArrayList<>();
        sequences.add(new EditedMediaItemSequence(Collections.singletonList(vb.build())));

        if (musicUri != null) {
            // الموسيقى سيكوانس تانية، بتتقص على طول المقطع الناتج وبتتدمج مع الفيديو
            MediaItem music = new MediaItem.Builder()
                    .setUri(musicUri)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionMs(outLen)
                            .build())
                    .build();
            EditedMediaItem musicItem = new EditedMediaItem.Builder(music)
                    .setRemoveVideo(true)
                    .build();
            sequences.add(new EditedMediaItemSequence(Collections.singletonList(musicItem)));
        }

        Composition.Builder cb = new Composition.Builder(sequences);
        if (speed != 1f && !mute) cb.experimentalSetForceAudioTrack(true);
        Composition composition = cb.build();

        outFile = new File(getCacheDir(), "out_" + System.currentTimeMillis() + ".mp4");

        transformer = new Transformer.Builder(this)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition comp, ExportResult result) {
                        exporting = false;
                        Plan.recordExport(MainActivity.this);
                        refreshPlan();
                        saveToGallery();
                    }

                    @Override
                    public void onError(Composition comp, ExportResult result,
                                        ExportException exception) {
                        exporting = false;
                        setBusy(false);
                        status.setText(getString(R.string.status_error, exception.getMessage()));
                    }
                })
                .build();

        exporting = true;
        setBusy(true);
        progress.setProgress(0);
        status.setText(R.string.status_exporting);
        transformer.start(composition, outFile.getAbsolutePath());
        handler.post(progressPoller);
    }

    private void saveToGallery() {
        status.setText(R.string.status_saving);
        progress.setProgress(100);
        new Thread(() -> {
            String err = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME,
                        "VideoCut_" + System.currentTimeMillis() + ".mp4");
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoCut");
                Uri dst = getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                if (dst == null) throw new IllegalStateException("insert failed");
                try (InputStream in = new FileInputStream(outFile);
                     OutputStream out = getContentResolver().openOutputStream(dst)) {
                    if (out == null) throw new IllegalStateException("no output stream");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            } catch (Exception ex) {
                err = ex.getMessage();
            } finally {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
            final String error = err;
            runOnUiThread(() -> {
                setBusy(false);
                if (error == null) status.setText(R.string.status_done);
                else status.setText(getString(R.string.status_error, error));
            });
        }).start();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? ProgressBar.VISIBLE : ProgressBar.INVISIBLE);
        if (!busy) progress.setIndeterminate(false);
        boolean ready = !busy && durationMs > 0;
        exportBtn.setEnabled(ready);
        transcribeBtn.setEnabled(ready);
        pickBtn.setEnabled(!busy);
        playBtn.setEnabled(!busy);
        startBar.setEnabled(ready);
        endBar.setEnabled(ready);
        speedSpinner.setEnabled(!busy);
        filterSpinner.setEnabled(!busy);
        musicBtn.setEnabled(!busy);
        clearMusicBtn.setEnabled(!busy);
        muteCheck.setEnabled(!busy);
    }

    private static String fmt(long ms) {
        long totalSec = ms / 1000;
        long tenths = (ms % 1000) / 100;
        return String.format(Locale.US, "%02d:%02d.%d", totalSec / 60, totalSec % 60, tenths);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (exporting && transformer != null) transformer.cancel();
        if (audioStage && audioTransformer != null) audioTransformer.cancel();
        if (player != null) player.release();
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}

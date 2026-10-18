package com.oneshoot.videocut;

import android.graphics.Bitmap;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;

import java.util.List;

/**
 * Overlay متغير بالوقت: بيرجّع صورة بحجم الإطار كله، عليها الكابشن الشغال في اللحظة دي
 * (أو صورة شفافة لو مفيش كابشن). الأوقات نسبية لبداية المقطع المقصوص.
 */
@OptIn(markerClass = UnstableApi.class)
public final class CaptionOverlay extends BitmapOverlay {

    private final int w, h;
    private final TextRender.Params style;
    private final List<Caption> caps;
    private final Bitmap empty;
    private Bitmap last;
    private int lastIdx = -2;

    public CaptionOverlay(int w, int h, TextRender.Params style, List<Caption> caps) {
        this.w = w;
        this.h = h;
        this.style = style;
        this.caps = caps;
        this.empty = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    @Override
    public Bitmap getBitmap(long presentationTimeUs) {
        long ms = presentationTimeUs / 1000;
        int idx = -1;
        for (int i = 0; i < caps.size(); i++) {
            Caption c = caps.get(i);
            if (ms >= c.startMs && ms < c.endMs) {
                idx = i;
                break;
            }
        }
        if (idx == lastIdx && last != null) return last;
        lastIdx = idx;
        if (idx < 0) {
            last = empty;
        } else {
            style.text = caps.get(idx).text;
            last = TextRender.bitmap(w, h, style);
        }
        return last;
    }
}

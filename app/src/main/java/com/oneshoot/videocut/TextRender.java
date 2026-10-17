package com.oneshoot.videocut;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** رسم النص العربي — نفس الدالة بتتستخدم في المعاينة وفي التصدير عشان الشكل يطلع متطابق. */
public final class TextRender {

    private TextRender() { }

    /** مكان الكابشن الثابت (نسبة من ارتفاع الفيديو). */
    public static final float CAPTION_Y = 0.82f;

    public static final class Params {
        public String text = "";
        public Typeface typeface = Typeface.DEFAULT;
        public int color = Color.WHITE;
        public float sizeFrac = 0.08f;   // حجم الخط كنسبة من عرض الفيديو
        public boolean stroke = true;    // حدود سودا حوالين الحروف
        public float x = 0.5f;           // مركز النص (0..1) من عرض الفيديو
        public float y = 0.15f;          // مركز النص (0..1) من ارتفاع الفيديو
    }

    /** نفس ستايل النص (خط/لون/حجم/حدود) بس في مكان الكابشن. */
    public static Params captionStyle(Params p, String text) {
        Params c = new Params();
        c.text = text;
        c.typeface = p.typeface;
        c.color = p.color;
        c.sizeFrac = p.sizeFrac;
        c.stroke = p.stroke;
        c.x = 0.5f;
        c.y = CAPTION_Y;
        return c;
    }

    public static void draw(Canvas c, int w, int h, Params p) {
        if (p.text == null || p.text.trim().isEmpty() || w <= 0 || h <= 0) return;

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(p.typeface);
        float size = Math.max(8f, p.sizeFrac * w);
        tp.setTextSize(size);

        int maxW = Math.max(1, (int) (w * 0.9f));
        StaticLayout layout = StaticLayout.Builder
                .obtain(p.text, 0, p.text.length(), tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build();

        c.save();
        c.translate(p.x * w - maxW / 2f, p.y * h - layout.getHeight() / 2f);
        if (p.stroke) {
            tp.setStyle(Paint.Style.STROKE);
            tp.setStrokeJoin(Paint.Join.ROUND);
            tp.setStrokeWidth(size * 0.14f);
            tp.setColor(Color.BLACK);
            layout.draw(c);
        }
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(p.color);
        layout.draw(c);
        c.restore();
    }

    /** صورة شفافة بحجم إطار الفيديو بالظبط، النص مرسوم عليها في مكانه. */
    public static Bitmap bitmap(int w, int h, Params p) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        draw(new Canvas(b), w, h, p);
        return b;
    }
}

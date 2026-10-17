package com.oneshoot.videocut;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** طبقة فوق المعاينة: بترسم النص والكابشن مكان ظهورهم في الفيديو، وبتسمح بسحب النص بالصباع. */
public class TextOverlayView extends View {

    private TextRender.Params params = new TextRender.Params();
    private String captionText = "";
    private int frameW, frameH;
    private final RectF rect = new RectF();

    public TextOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setParams(TextRender.Params p) {
        params = p;
        invalidate();
    }

    public void setFrame(int w, int h) {
        frameW = w;
        frameH = h;
        invalidate();
    }

    public void setCaption(String t) {
        if (t == null) t = "";
        if (!t.equals(captionText)) {
            captionText = t;
            invalidate();
        }
    }

    private void computeRect() {
        if (frameW <= 0 || frameH <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            rect.setEmpty();
            return;
        }
        float s = Math.min(getWidth() / (float) frameW, getHeight() / (float) frameH);
        float rw = frameW * s;
        float rh = frameH * s;
        rect.set((getWidth() - rw) / 2f, (getHeight() - rh) / 2f,
                (getWidth() + rw) / 2f, (getHeight() + rh) / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        computeRect();
        if (rect.isEmpty()) return;
        canvas.save();
        canvas.translate(rect.left, rect.top);
        int w = (int) rect.width();
        int h = (int) rect.height();
        TextRender.draw(canvas, w, h, params);
        if (!captionText.isEmpty()) {
            TextRender.draw(canvas, w, h, TextRender.captionStyle(params, captionText));
        }
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        computeRect();
        if (rect.isEmpty()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                float x = (e.getX() - rect.left) / rect.width();
                float y = (e.getY() - rect.top) / rect.height();
                params.x = Math.max(0f, Math.min(1f, x));
                params.y = Math.max(0f, Math.min(1f, y));
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}

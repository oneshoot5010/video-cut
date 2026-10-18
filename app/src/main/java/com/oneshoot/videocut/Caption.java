package com.oneshoot.videocut;

/** سطر كابشن واحد: وقت البداية والنهاية بالمللي ثانية والنص. */
public final class Caption {
    public final long startMs;
    public final long endMs;
    public final String text;

    public Caption(long startMs, long endMs, String text) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
    }
}

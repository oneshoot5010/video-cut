package com.oneshoot.videocut;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * الخطة: مجاني (حد يومي للتصدير + علامة مائية) أو برو (بلا حد وبلا علامة).
 * التفعيل الحقيقي للبرو هيتم من Google Play Billing: لما الدفع يتأكد، ينادي setPro(ctx, true).
 */
public final class Plan {

    public static final int FREE_DAILY_LIMIT = 3;

    private static final String PREFS = "vc";
    private static final String KEY_PRO = "pro";
    private static final String KEY_DAY = "day";
    private static final String KEY_COUNT = "count";

    private Plan() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static boolean isPro(Context c) {
        return prefs(c).getBoolean(KEY_PRO, false);
    }

    public static void setPro(Context c, boolean pro) {
        prefs(c).edit().putBoolean(KEY_PRO, pro).apply();
    }

    public static int remainingToday(Context c) {
        if (isPro(c)) return Integer.MAX_VALUE;
        SharedPreferences p = prefs(c);
        if (!today().equals(p.getString(KEY_DAY, ""))) return FREE_DAILY_LIMIT;
        return Math.max(0, FREE_DAILY_LIMIT - p.getInt(KEY_COUNT, 0));
    }

    public static void recordExport(Context c) {
        if (isPro(c)) return;
        SharedPreferences p = prefs(c);
        String day = today();
        int count = day.equals(p.getString(KEY_DAY, "")) ? p.getInt(KEY_COUNT, 0) : 0;
        p.edit().putString(KEY_DAY, day).putInt(KEY_COUNT, count + 1).apply();
    }
}

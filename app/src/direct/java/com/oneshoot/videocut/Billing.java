package com.oneshoot.videocut;

import android.app.Activity;
import android.widget.Toast;

/** نسخة direct مفيهاش شراء: الاشتراك بيتم من Google Play بس. */
public final class Billing {

    private Billing() { }

    public static void init(Activity a, Runnable changed) { }

    public static void buy(Activity a) {
        Toast.makeText(a, R.string.pro_store_only, Toast.LENGTH_LONG).show();
    }
}

package com.oneshoot.videocut;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.List;

/** الاشتراك عن طريق Google Play (نسخة play). الـ Product ID لازم يتطابق مع اللي في Play Console. */
public final class Billing {

    static final String PRO_PRODUCT_ID = "videocut_pro";

    private static BillingClient client;
    private static ProductDetails proDetails;
    private static Context appCtx;
    private static Runnable onChange;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Billing() { }

    public static void init(Activity a, Runnable changed) {
        appCtx = a.getApplicationContext();
        onChange = changed;
        if (client != null) return;
        client = BillingClient.newBuilder(appCtx)
                .setListener(listener)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .enableAutoServiceReconnection()
                .build();
        connect();
    }

    private static void connect() {
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult r) {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    loadProduct();
                    restore();
                }
            }

            @Override
            public void onBillingServiceDisconnected() { }
        });
    }

    private static void loadProduct() {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(
                        QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(PRO_PRODUCT_ID)
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build()))
                .build();
        client.queryProductDetailsAsync(params, (r, result) -> {
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
            List<ProductDetails> list = result.getProductDetailsList();
            if (!list.isEmpty()) proDetails = list.get(0);
        });
    }

    // بيتأكد من المشتريات القديمة (تغيير موبايل، تثبيت تاني)
    private static void restore() {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        client.queryPurchasesAsync(params, (r, purchases) -> {
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
            for (Purchase p : purchases) handle(p);
        });
    }

    private static final PurchasesUpdatedListener listener = (r, purchases) -> {
        if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase p : purchases) handle(p);
        }
    };

    private static void handle(Purchase p) {
        if (!p.getProducts().contains(PRO_PRODUCT_ID)) return;
        if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;
        if (!p.isAcknowledged()) {
            AcknowledgePurchaseParams ack = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(p.getPurchaseToken())
                    .build();
            client.acknowledgePurchase(ack, r -> { });
        }
        Plan.setPro(appCtx, true);
        main.post(() -> {
            if (onChange != null) onChange.run();
        });
    }

    public static void buy(Activity a) {
        if (Plan.isPro(a)) {
            Toast.makeText(a, R.string.pro_already, Toast.LENGTH_SHORT).show();
            return;
        }
        if (client == null || proDetails == null) {
            Toast.makeText(a, R.string.pro_loading, Toast.LENGTH_SHORT).show();
            if (client != null) loadProduct();
            return;
        }
        BillingFlowParams flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(proDetails)
                                .build()))
                .build();
        BillingResult r = client.launchBillingFlow(a, flow);
        if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            Toast.makeText(a, R.string.pro_failed, Toast.LENGTH_SHORT).show();
        }
    }
}

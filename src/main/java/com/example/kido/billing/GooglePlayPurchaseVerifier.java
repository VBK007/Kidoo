package com.example.kido.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Production verifier: validates the purchase token with the Google Play
 * Developer API. Active when {@code app.billing.verify=google}.
 *
 * TODO: implement using the Android Publisher API:
 *   1. Load a service-account credential (JSON) with the "androidpublisher" scope.
 *   2. Call purchases.subscriptions.get(packageName, productId, purchaseToken)
 *      (or purchases.products.get for one-off products).
 *   3. Accept only when paymentState == RECEIVED/1 and expiryTimeMillis is in the future,
 *      then optionally acknowledge the purchase.
 * Requires the google-api-services-androidpublisher dependency (ask before adding —
 * it touches the network) and the store package name in config.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.billing.verify", havingValue = "google")
public class GooglePlayPurchaseVerifier implements PurchaseVerifier {
    @Override
    public boolean verify(String platform, String productId, String purchaseToken) {
        log.warn("GooglePlayPurchaseVerifier is not implemented yet — rejecting purchase {}", productId);
        return false; // fail closed until the Play API call is wired
    }
}

package com.example.kido.billing;

/** Verifies a store purchase before entitlement is granted. */
public interface PurchaseVerifier {
    /** @return true if the purchase token is valid for the given product. */
    boolean verify(String platform, String productId, String purchaseToken);
}

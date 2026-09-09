package com.example.kido.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development verifier: accepts any non-blank purchase token. Active unless
 * {@code app.billing.verify=google}. Never use in production.
 */
@Component
@ConditionalOnProperty(name = "app.billing.verify", havingValue = "dev", matchIfMissing = true)
public class DevPurchaseVerifier implements PurchaseVerifier {
    @Override
    public boolean verify(String platform, String productId, String purchaseToken) {
        return purchaseToken != null && !purchaseToken.isBlank();
    }
}

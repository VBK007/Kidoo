package com.example.kido.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Development verifier: accepts any non-blank purchase token. Active unless
 * {@code app.billing.verify=google}. Never use in production.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.billing.verify", havingValue = "dev", matchIfMissing = true)
public class DevPurchaseVerifier implements PurchaseVerifier {
    @Override
    public boolean verify(String platform, String productId, String purchaseToken) {
        boolean ok = purchaseToken != null && !purchaseToken.isBlank();
        log.warn("DevPurchaseVerifier accepting platform='{}' productId='{}' without real verification (result={})",
                platform, productId, ok);
        return ok;
    }
}

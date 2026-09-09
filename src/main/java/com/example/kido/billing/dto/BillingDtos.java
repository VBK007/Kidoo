package com.example.kido.billing.dto;

import java.time.Instant;
import java.util.List;

import com.example.kido.user.AppUser;

/** Billing / entitlement DTOs. */
public final class BillingDtos {
    private BillingDtos() {}

    public record PlanDto(String id, String name, String priceLabel, String period, List<String> features) {}

    public record SubscriptionDto(boolean premium, String plan, Instant premiumUntil, boolean active) {
        public static SubscriptionDto from(AppUser u) {
            var s = u.getSubscription();
            return new SubscriptionDto(s.isPremium(), s.getPlan(), s.getPremiumUntil(), s.isActive());
        }
    }

    public record EntitlementsDto(boolean isPremium, boolean adsDisabled, boolean limitRemoved) {}

    public record VerifyRequest(String platform, String productId, String purchaseToken) {}
}

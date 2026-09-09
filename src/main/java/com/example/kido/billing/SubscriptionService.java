package com.example.kido.billing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.billing.dto.BillingDtos.EntitlementsDto;
import com.example.kido.billing.dto.BillingDtos.PlanDto;
import com.example.kido.billing.dto.BillingDtos.SubscriptionDto;
import com.example.kido.billing.dto.BillingDtos.VerifyRequest;
import com.example.kido.common.ApiException;
import com.example.kido.user.AppUser;
import com.example.kido.user.Subscription;
import com.example.kido.user.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SubscriptionService {

    private static final List<PlanDto> PLANS = List.of(
            new PlanDto("free", "Free", "₹0", "forever",
                    List.of("7 learning worlds", "Includes ads", "30 min/day limit")),
            new PlanDto("kiduu_plus_monthly", "Kiduu Plus", "₹149", "month",
                    List.of("No ads", "No time limit", "All worlds unlocked", "Offline downloads")),
            new PlanDto("kiduu_plus_annual", "Kiduu Plus (Yearly)", "₹1,199", "year",
                    List.of("No ads", "No time limit", "All worlds unlocked", "Offline downloads", "Save 33%")));

    private final UserRepository users;
    private final PurchaseVerifier verifier;

    public SubscriptionService(UserRepository users, PurchaseVerifier verifier) {
        this.users = users;
        this.verifier = verifier;
    }

    public List<PlanDto> plans() {
        return PLANS;
    }

    public SubscriptionDto get(AppUser owner) {
        return SubscriptionDto.from(reload(owner));
    }

    public EntitlementsDto entitlements(AppUser owner) {
        boolean active = reload(owner).getSubscription().isActive();
        return new EntitlementsDto(active, active, active);
    }

    /**
     * Verify a store purchase and grant entitlement.
     * TODO: verify {@code purchaseToken} with the Google Play Developer API before granting.
     * For now this trusts the request and activates the matching plan.
     */
    public SubscriptionDto verify(AppUser owner, VerifyRequest req) {
        if (req.productId() == null || PLANS.stream().noneMatch(p -> p.id().equals(req.productId()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown productId");
        }
        if (!verifier.verify(req.platform(), req.productId(), req.purchaseToken())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Purchase could not be verified");
        }
        AppUser u = reload(owner);
        Subscription s = u.getSubscription();
        boolean annual = req.productId().contains("annual");
        s.setPremium(true);
        s.setPlan(req.productId());
        s.setPremiumUntil(Instant.now().plus(annual ? 365 : 30, ChronoUnit.DAYS));
        SubscriptionDto dto = SubscriptionDto.from(users.save(u));
        log.info("Activated {} for account {} until {}", req.productId(), owner.getId(), s.getPremiumUntil());
        return dto;
    }

    private AppUser reload(AppUser owner) {
        return users.findById(owner.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account not found"));
    }
}

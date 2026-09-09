package com.example.kido.billing;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.billing.dto.BillingDtos.EntitlementsDto;
import com.example.kido.billing.dto.BillingDtos.PlanDto;
import com.example.kido.billing.dto.BillingDtos.SubscriptionDto;
import com.example.kido.billing.dto.BillingDtos.VerifyRequest;
import com.example.kido.user.AppUser;

@RestController
public class SubscriptionController {

    private final SubscriptionService service;

    public SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/api/plans")
    public List<PlanDto> plans() {
        return service.plans();
    }

    @GetMapping("/api/subscription")
    public SubscriptionDto subscription(@AuthenticationPrincipal AppUser user) {
        return service.get(user);
    }

    @PostMapping("/api/subscription/verify")
    public SubscriptionDto verify(@AuthenticationPrincipal AppUser user, @RequestBody VerifyRequest req) {
        return service.verify(user, req);
    }

    @GetMapping("/api/entitlements")
    public EntitlementsDto entitlements(@AuthenticationPrincipal AppUser user) {
        return service.entitlements(user);
    }
}

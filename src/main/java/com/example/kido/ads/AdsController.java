package com.example.kido.ads;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.billing.SubscriptionService;
import com.example.kido.user.AppUser;

/**
 * Ad configuration for the free tier. Returns an empty config for Kiduu Plus
 * members (premium = no ads). The app runs the actual ad SDK client-side.
 */
@RestController
public class AdsController {

    public record Placement(String screen, String type, int everyNScreens) {}

    public record AdConfigDto(boolean adsEnabled, String provider, List<Placement> placements) {}

    private static final List<Placement> FREE_PLACEMENTS = List.of(
            new Placement("home", "banner", 0),
            new Placement("world_complete", "interstitial", 3),
            new Placement("daily_limit_hit", "rewarded", 0));

    private final SubscriptionService subscriptions;

    public AdsController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping("/api/ads/config")
    public AdConfigDto config(@AuthenticationPrincipal AppUser user) {
        boolean premium = subscriptions.entitlements(user).adsDisabled();
        if (premium) {
            return new AdConfigDto(false, "admob", List.of());
        }
        return new AdConfigDto(true, "admob", FREE_PLACEMENTS);
    }
}

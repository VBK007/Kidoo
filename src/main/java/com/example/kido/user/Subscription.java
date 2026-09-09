package com.example.kido.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Kiduu Plus subscription state, embedded in the user row. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {
    private boolean premium = false;
    @Column(name = "sub_plan")
    private String plan;          // productId, e.g. kiduu_plus_monthly
    private Instant premiumUntil;

    public boolean isActive() {
        return premium && premiumUntil != null && premiumUntil.isAfter(Instant.now());
    }
}

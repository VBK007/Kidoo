package com.example.kido.user;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Account-wide (parent) settings, embedded in the user row. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AccountSettings {
    private String language = "en";           // en | hi | ta
    private int dailyScreenTimeMin = 30;
    private boolean askBeforePurchases = true;
    private boolean weeklyEmailSummary = false;
}

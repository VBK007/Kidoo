package com.example.kido.security;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Someone watching one title in one party, with no account behind them.
 *
 * <p>Deliberately not an {@code AppUser}. A guest has no row in the user table, no
 * profile, no household and no library — inventing a throwaway account for them would
 * put all of that within reach and leave rows behind after the party ended. Being a
 * different principal type is what makes the narrow permission enforceable rather than
 * merely intended: {@code ActiveProfileResolver} throws for any principal that is not
 * an {@code AppUser}, so every profile-scoped endpoint in the application refuses a
 * guest without any of them knowing guests exist.
 *
 * <p>What a guest can do is therefore exactly: play {@code mediaItemId}, while
 * {@code partyId} is still live. Nothing else in the API is reachable.
 *
 * @param memberId    the {@code WatchPartyMember} row this token was issued for
 * @param partyId     re-checked on every request, because a party can end long before
 *                    the token would have expired
 * @param mediaItemId the one title this token unlocks
 * @param displayName what the host admitted; carried for logs and error messages
 */
public record GuestPrincipal(String memberId,
                             String partyId,
                             String mediaItemId,
                             String displayName) {

    public static final String ROLE = "ROLE_GUEST";

    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority(ROLE));
    }

    /** Whether this token unlocks a given title. */
    public boolean mayPlay(String itemId) {
        return mediaItemId != null && mediaItemId.equals(itemId);
    }
}

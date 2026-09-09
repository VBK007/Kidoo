package com.example.kido.account;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.user.AppUser;
import com.example.kido.user.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ParentPinService {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public ParentPinService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public boolean isSet(AppUser owner) {
        return reload(owner).getParentPinHash() != null;
    }

    public void setPin(AppUser owner, String pin) {
        AppUser u = reload(owner);
        u.setParentPinHash(encoder.encode(pin));  // BCrypt — never stored in plaintext
        users.save(u);
        log.info("Parent PIN set for account={}", owner.getId());
    }

    public boolean verify(AppUser owner, String pin) {
        String hash = reload(owner).getParentPinHash();
        boolean ok = hash != null && encoder.matches(pin, hash);
        if (!ok) {
            log.warn("Parent PIN verification failed for account={}", owner.getId());
        }
        return ok;
    }

    private AppUser reload(AppUser owner) {
        return users.findById(owner.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account not found"));
    }
}

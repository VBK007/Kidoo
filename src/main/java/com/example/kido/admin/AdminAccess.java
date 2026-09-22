package com.example.kido.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.kido.common.ApiException;
import com.example.kido.user.AppUser;
import com.example.kido.user.Role;

/**
 * The same owner gate the media admin panel and the poster authoring endpoints use:
 * a {@code PARENT} account <em>and</em> the {@code X-Admin-Key} header.
 *
 * <p>Both halves are needed for the same reasons they are there already. The key alone
 * would let any client that shipped with it act as owner; the role alone would let a
 * second parent in a shared household read the whole household's figures from the
 * phone app rather than from the console its owner runs.
 *
 * @see com.example.kido.media.admin.AdminController
 */
@Component
public class AdminAccess {

    private final String adminKey;

    public AdminAccess(@Value("${app.admin.api-key}") String adminKey) {
        this.adminKey = adminKey;
    }

    /**
     * Returns 403 without saying which of the two checks failed, so the response
     * cannot be used to confirm a guessed key.
     */
    public void require(AppUser user, String key) {
        boolean keyOk = adminKey != null && !adminKey.isBlank() && adminKey.equals(key);
        boolean roleOk = user != null && user.getRole() == Role.PARENT;
        if (!keyOk || !roleOk) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "The admin dashboard is owner-only and requires a valid admin key");
        }
    }
}

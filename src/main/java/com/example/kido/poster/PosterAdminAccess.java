package com.example.kido.poster;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.kido.common.ApiException;
import com.example.kido.user.AppUser;
import com.example.kido.user.Role;

/**
 * Who may author the template catalog.
 *
 * <p>Templates are catalog content the household's owner publishes, not something a
 * child creates, so writing follows the same rule as the rest of the admin surface:
 * a parent account <i>and</i> the {@code X-Admin-Key} header. Reading needs only a
 * signed-in profile — the security config's default already sees to that.
 *
 * @see com.example.kido.media.admin.AdminController
 */
@Component
class PosterAdminAccess {

    private final String adminKey;

    PosterAdminAccess(@Value("${app.admin.api-key}") String adminKey) {
        this.adminKey = adminKey;
    }

    /**
     * Returns 403 without saying which of the two checks failed, so the response
     * cannot be used to confirm a guessed key.
     */
    void require(AppUser user, String key) {
        boolean keyOk = adminKey != null && !adminKey.isBlank() && adminKey.equals(key);
        boolean roleOk = user != null && user.getRole() == Role.PARENT;
        if (!keyOk || !roleOk) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Editing poster templates is owner-only and requires a valid admin key");
        }
    }
}

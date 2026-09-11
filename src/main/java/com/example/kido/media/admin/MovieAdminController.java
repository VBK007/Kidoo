package com.example.kido.media.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.kido.common.ApiException;
import com.example.kido.media.dto.MovieAdminDtos.MovieAdminDto;
import com.example.kido.media.dto.MovieAdminDtos.MovieUpsertRequest;
import com.example.kido.user.AppUser;
import com.example.kido.user.Role;

import jakarta.validation.Valid;

/**
 * One endpoint for adding a title and for editing one, thumbnail included.
 *
 * <p>Insert and update are the same call because they are the same form. The client
 * sends {@code id} when it has one and leaves it out when it does not, which is the
 * only difference between the two — and it means an edit screen and an add screen can
 * be the same screen, submitting to the same place.
 *
 * <p>It accepts two content types for the same reason. A form with a chosen file sends
 * {@code multipart/form-data}: a JSON part named {@code movie} and file parts named
 * {@code poster} and {@code backdrop}. A form with no file to send may post plain JSON
 * instead, rather than having to construct a multipart body to say nothing about
 * artwork. Both land in the same place and behave identically.
 *
 * <p>Owner only, with the same two checks as the rest of the admin surface: a PARENT
 * account <em>and</em> the admin key. Failure is 403 either way, so the response cannot
 * confirm a guessed key.
 */
@RestController
@RequestMapping("/api/media/admin/movies")
public class MovieAdminController {

    private final MovieAdminService service;
    private final String adminKey;

    public MovieAdminController(MovieAdminService service,
                                @Value("${app.admin.api-key}") String adminKey) {
        this.service = service;
        this.adminKey = adminKey;
    }

    /**
     * Creates a title, or updates the one named by {@code movie.id}.
     *
     * @param movie    the JSON part: every field the movie form can set
     * @param poster   the thumbnail; omit to leave the existing one alone
     * @param backdrop the wide still behind the detail screen; likewise optional
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MovieAdminDto> upsertWithArtwork(
            @AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @Valid @RequestPart("movie") MovieUpsertRequest movie,
            @RequestPart(value = "poster", required = false) MultipartFile poster,
            @RequestPart(value = "backdrop", required = false) MultipartFile backdrop) {
        requireOwner(user, key);
        return respond(service.upsert(movie, poster, backdrop));
    }

    /** The same call for a client with no artwork to send. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MovieAdminDto> upsert(
            @AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @Valid @RequestBody MovieUpsertRequest movie) {
        requireOwner(user, key);
        return respond(service.upsert(movie, null, null));
    }

    /** 201 for something that did not exist a moment ago, 200 for an edit. */
    private static ResponseEntity<MovieAdminDto> respond(MovieAdminDto saved) {
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(saved);
    }

    /**
     * Requires both the admin key and a parent account, returning 403 without saying
     * which check failed so the response cannot confirm a guessed key.
     */
    private void requireOwner(AppUser user, String key) {
        boolean keyOk = adminKey != null && !adminKey.isBlank() && adminKey.equals(key);
        boolean roleOk = user != null && user.getRole() == Role.PARENT;
        if (!keyOk || !roleOk) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Owner access required: a PARENT account and the admin key");
        }
    }
}

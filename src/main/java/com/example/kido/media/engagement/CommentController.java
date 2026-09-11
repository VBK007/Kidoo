package com.example.kido.media.engagement;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.EngagementDtos.CommentDto;
import com.example.kido.media.dto.EngagementDtos.CommentPageDto;
import com.example.kido.media.dto.EngagementDtos.CommentRequest;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;
import com.example.kido.user.AppUser;

import jakarta.validation.Valid;

/**
 * The comment thread under a title.
 *
 * <p>Scoped to the profile in {@code X-Profile-Id}, like the like button: a household
 * shares one login, and "who said this" has to mean a person rather than an account.
 * The account comes in alongside it, because who may take a comment down is a question
 * about the account's role and not about the profile.
 *
 * <p>Comments are visible to everyone who can see the title, children included. There
 * is no profanity filter and no approval queue — a parent removes what should not
 * stand, which is the moderation a household of this size actually needs.
 */
@RestController
@RequestMapping("/api/media/items/{id}/comments")
public class CommentController {

    private final CommentService service;

    public CommentController(CommentService service) {
        this.service = service;
    }

    /** Newest first. Default page size 20, capped at 100. */
    @GetMapping
    public CommentPageDto list(@ActiveProfile Profile profile,
                               @AuthenticationPrincipal AppUser user,
                               @PathVariable String id,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return service.list(profile, user, id, page, size);
    }

    @PostMapping
    public ResponseEntity<CommentDto> post(@ActiveProfile Profile profile,
                                           @AuthenticationPrincipal AppUser user,
                                           @PathVariable String id,
                                           @Valid @RequestBody CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.post(profile, user, id, request.body()));
    }

    /** The author rewrites their own; anyone else gets **403**. */
    @PutMapping("/{commentId}")
    public CommentDto edit(@ActiveProfile Profile profile,
                           @AuthenticationPrincipal AppUser user,
                           @PathVariable String id,
                           @PathVariable String commentId,
                           @Valid @RequestBody CommentRequest request) {
        return service.edit(profile, user, id, commentId, request.body());
    }

    /** The author, or a PARENT on the same account. */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@ActiveProfile Profile profile,
                                       @AuthenticationPrincipal AppUser user,
                                       @PathVariable String id,
                                       @PathVariable String commentId) {
        service.delete(profile, user, id, commentId);
        return ResponseEntity.noContent().build();
    }
}

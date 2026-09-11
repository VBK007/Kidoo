package com.example.kido.media.downloads;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.dto.DownloadDtos.UpdateMediaSettingsRequest;
import com.example.kido.profile.Profile;

/** Reads and updates a profile's media preferences, creating a default row on demand. */
@Service
public class MediaSettingsService {

    private final ProfileMediaSettingsRepository repository;

    public MediaSettingsService(ProfileMediaSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * The profile's settings, defaulted if never saved.
     *
     * <p>Read-write rather than read-only: a profile that has never opened settings
     * still needs a row, and creating it lazily here keeps every caller from having to
     * handle an absent one.
     */
    @Transactional
    public ProfileMediaSettings forProfile(Profile profile) {
        return repository.findByProfileId(profile.getId())
                .orElseGet(() -> repository.save(ProfileMediaSettings.builder()
                        .profileId(profile.getId())
                        .build()));
    }

    @Transactional
    public ProfileMediaSettings update(Profile profile, UpdateMediaSettingsRequest request) {
        ProfileMediaSettings settings = forProfile(profile);

        if (request.awayBehaviour() != null && !request.awayBehaviour().isBlank()) {
            settings.setAwayBehaviour(parseBehaviour(request.awayBehaviour()));
        }
        if (request.downloadHeight() != null) {
            settings.setDownloadHeight(request.downloadHeight());
        }
        if (request.awayMaxHeight() != null) {
            settings.setAwayMaxHeight(request.awayMaxHeight());
        }
        if (request.showTechnicalBadges() != null) {
            settings.setShowTechnicalBadges(request.showTechnicalBadges());
        }
        if (request.preferredLanguage() != null) {
            // Blank is "no language chosen", not the empty string: a column holding ""
            // would satisfy every null check downstream and mean nothing.
            String code = request.preferredLanguage().trim().toLowerCase(Locale.ROOT);
            settings.setPreferredLanguage(code.isEmpty() ? null : code);
        }
        if (request.preferredGenres() != null) {
            // Replaced wholesale rather than merged. The client holds the entire list on
            // screen when it sends this, so a merge could only ever add genres somebody
            // has just deselected.
            settings.getPreferredGenres().clear();
            settings.getPreferredGenres().addAll(cleanGenres(request.preferredGenres()));
        }
        settings.setUpdatedAt(Instant.now());
        return repository.save(settings);
    }

    /**
     * Trims, drops blanks and de-duplicates case-insensitively, keeping the order sent.
     *
     * <p>Casing is preserved as given, since these are shown back to the person who
     * chose them and "sci-fi" reads better than "SCI-FI". Matching against the library
     * is case-insensitive anyway.
     */
    private static Set<String> cleanGenres(List<String> raw) {
        Set<String> seen = new HashSet<>();
        Set<String> cleaned = new LinkedHashSet<>();
        for (String genre : raw) {
            if (genre == null) {
                continue;
            }
            String trimmed = genre.trim();
            if (!trimmed.isEmpty() && seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                cleaned.add(trimmed);
            }
        }
        return cleaned;
    }

    private static ProfileMediaSettings.AwayBehaviour parseBehaviour(String raw) {
        try {
            return ProfileMediaSettings.AwayBehaviour.valueOf(
                    raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unknown away behaviour '" + raw + "' (expected ASK, SAVED_ONLY or STREAM)");
        }
    }
}

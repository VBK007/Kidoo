package com.example.kido.media.engagement.mirror;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.engagement.mirror.MirrorSavedDtos.SaveRequest;
import com.example.kido.media.engagement.mirror.MirrorSavedDtos.SavedItemDto;
import com.example.kido.media.engagement.mirror.MirrorSavedDtos.SavedPageDto;
import com.example.kido.media.engagement.mirror.MirrorSavedDtos.SavedStatusDto;
import com.example.kido.profile.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * The My List button for mirror titles.
 *
 * <p>A watchlist the catalogue side has no equivalent of, because a mirror title has no
 * row of ours to flag. It behaves like the mirror like in every way that matters — keyed
 * on profile so a household keeps separate lists, idempotent so a double tap is harmless,
 * {@link MirrorIds#require} sanity-bounds the id in place of an existence check — and
 * differs only in carrying a title snapshot, so the list it feeds can draw its posters
 * without a round trip to the mirror per tile.
 */
@Slf4j
@Service
public class MirrorSavedService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MirrorSavedRepository saved;

    public MirrorSavedService(MirrorSavedRepository saved) {
        this.saved = saved;
    }

    /**
     * Put a title on this profile's list, with the snapshot the list will draw from.
     *
     * <p>Idempotent: saving something already saved keeps the original row — its place in
     * the list and the snapshot it was saved with — rather than bumping it to the top or
     * overwriting its art. A list is a set, and re-tapping the bookmark on a title already
     * on it asks for nothing to change.
     */
    @Transactional
    public SavedStatusDto save(Profile profile, String mirrorId, SaveRequest request) {
        String id = MirrorIds.require(mirrorId);
        if (!saved.existsByProfileIdAndMirrorId(profile.getId(), id)) {
            try {
                saved.saveAndFlush(MirrorSavedItem.builder()
                        .profileId(profile.getId())
                        .mirrorId(id)
                        .name(cleanName(request == null ? null : request.name()))
                        .posterUrl(trimToNull(request == null ? null : request.posterUrl()))
                        .year(request == null ? null : request.year())
                        .ott(trimToNull(request == null ? null : request.ott()))
                        .type(trimToNull(request == null ? null : request.type()))
                        .build());
                log.info("Profile {} saved mirror title {}", profile.getId(), id);
            } catch (DataIntegrityViolationException ex) {
                // Two taps raced past the exists check; the unique constraint held and the
                // end state is the one the caller asked for.
                log.debug("Duplicate mirror save for profile {} on {}", profile.getId(), id);
            }
        }
        return new SavedStatusDto(id, true);
    }

    /** Idempotent in the same way: removing something not on the list simply reports gone. */
    @Transactional
    public SavedStatusDto remove(Profile profile, String mirrorId) {
        String id = MirrorIds.require(mirrorId);
        saved.deleteByProfileIdAndMirrorId(profile.getId(), id);
        return new SavedStatusDto(id, false);
    }

    @Transactional(readOnly = true)
    public SavedStatusDto status(Profile profile, String mirrorId) {
        String id = MirrorIds.require(mirrorId);
        return new SavedStatusDto(id, saved.existsByProfileIdAndMirrorId(profile.getId(), id));
    }

    /** This profile's list, newest first. Default page size 30, capped at 100. */
    @Transactional(readOnly = true)
    public SavedPageDto list(Profile profile, int page, int size) {
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<MirrorSavedItem> found = saved.findByProfileIdOrderByCreatedAtDescIdDesc(
                profile.getId(), PageRequest.of(Math.max(0, page), pageSize));

        List<SavedItemDto> items = found.getContent().stream()
                .map(this::toDto)
                .toList();

        return new SavedPageDto(items, found.getNumber(), found.getSize(),
                found.getTotalElements(), found.getTotalPages());
    }

    /**
     * Which of {@code mirrorIds} this profile has saved, as one query.
     *
     * <p>Every mirror rail that draws a bookmark glyph calls this once with the whole page,
     * exactly as it calls {@code likedMirrorIds} for the hearts.
     */
    @Transactional(readOnly = true)
    public Set<String> savedMirrorIds(Profile profile, Collection<String> mirrorIds) {
        if (profile == null || mirrorIds == null || mirrorIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(saved.findSavedMirrorIds(profile.getId(), List.copyOf(mirrorIds)));
    }

    private SavedItemDto toDto(MirrorSavedItem item) {
        return new SavedItemDto(
                item.getMirrorId(),
                item.getName(),
                item.getPosterUrl(),
                item.getYear(),
                item.getOtt(),
                item.getType(),
                item.getCreatedAt().toString());
    }

    /**
     * Trimmed, and refused if that leaves nothing.
     *
     * <p>The name is the one field a list row cannot be drawn without, so a blank one —
     * past bean validation, which only caps the length — is turned away here rather than
     * saved as a nameless tile.
     */
    private String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A title needs a name to be saved");
        }
        if (trimmed.length() > MirrorSavedItem.NAME_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That title name is too long");
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

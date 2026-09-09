package com.example.kido.media.matchfix;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.catalog.MetadataSource;
import com.example.kido.media.dto.MatchFixDtos.ApplyMatchRequest;
import com.example.kido.media.dto.MatchFixDtos.CandidateDto;
import com.example.kido.media.dto.MatchFixDtos.FixResultDto;
import com.example.kido.media.dto.MatchFixDtos.FixScreenDto;
import com.example.kido.media.dto.MatchFixDtos.MismatchDto;
import com.example.kido.media.dto.MatchFixDtos.MismatchQueueDto;
import com.example.kido.media.metadata.FilenameParser;
import com.example.kido.media.trickplay.TrickplayService;

import lombok.extern.slf4j.Slf4j;

/**
 * The fix-wrong-metadata workflow: find the items the scanner probably got wrong, offer
 * candidates, and apply the owner's answer so it sticks.
 *
 * <h2>What this never does</h2>
 * Nothing here touches the disk. No file is renamed, moved or deleted — the screen
 * promises that explicitly, and the promise text is served from here so the client
 * cannot drift away from what the server actually does. A correction changes database
 * rows only, which is also why it has to be protected from the next rescan.
 */
@Slf4j
@Service
public class MatchFixService {

    /** Ranking floor: below this a suggestion is noise rather than a candidate. */
    private static final int MIN_USEFUL_MATCH = 25;

    private static final String REASSURANCE =
            "We guessed this from the filename and may have got it wrong. "
                    + "Choosing the right title only changes what the app shows — "
                    + "nothing on the disk is renamed, moved or deleted.";

    private final MediaItemRepository items;
    private final MetadataCandidateProvider candidates;
    private final FilenameParser filenames;
    private final TrickplayService trickplay;

    public MatchFixService(MediaItemRepository items,
                           MetadataCandidateProvider candidates,
                           FilenameParser filenames,
                           TrickplayService trickplay) {
        this.items = items;
        this.candidates = candidates;
        this.filenames = filenames;
        this.trickplay = trickplay;
    }

    /**
     * Items whose metadata came from a filename guess.
     *
     * <p>That is the honest signal available: a guessed title may be perfect, but it is
     * the only population where a mistake is likely, and anything confirmed by a sidecar
     * or by the owner is excluded. Items already hidden as deliberately unmatched are
     * excluded too — the owner has answered for those.
     */
    @Transactional(readOnly = true)
    public MismatchQueueDto queue(int page, int size) {
        int pageSize = Math.min(Math.max(1, size), 100);
        Page<MediaItem> results = items.findAll(
                suspected(),
                PageRequest.of(Math.max(0, page), pageSize,
                        Sort.by(Sort.Direction.ASC, "sortTitle")));

        return new MismatchQueueDto(
                results.getContent().stream().map(MatchFixService::toMismatch).toList(),
                results.getNumber(),
                results.getSize(),
                results.getTotalElements(),
                results.getTotalPages());
    }

    @Transactional(readOnly = true)
    public long remaining() {
        return items.count(suspected());
    }

    /**
     * The fix screen for one item, with candidates ranked by how well they match.
     *
     * @param query what the owner typed, or null to suggest from the file alone
     */
    @Transactional(readOnly = true)
    public FixScreenDto fixScreen(String itemId, String query, int limit) {
        MediaItem item = require(itemId);
        return new FixScreenDto(
                toMismatch(item),
                rankedCandidates(item, query, limit),
                candidates.sourceName(),
                REASSURANCE,
                remaining());
    }

    /**
     * Scores every candidate against the query if there is one, and against the file
     * itself otherwise.
     *
     * <p>Scoring against the query when present is what makes the search field work: a
     * candidate list ranked against the wrong existing title would keep the wrong answer
     * at the top no matter what the owner searched for.
     */
    @Transactional(readOnly = true)
    public List<CandidateDto> rankedCandidates(MediaItem item, String query, int limit) {
        int capped = Math.min(Math.max(1, limit), 50);

        String reference;
        Integer referenceYear;
        if (query != null && !query.isBlank()) {
            FilenameParser.Parsed typed = filenames.parse(query.trim());
            reference = typed.year() != null ? typed.title() : query.trim();
            referenceYear = typed.year();
        } else {
            // No query: rank against the filename, which is the only evidence there is.
            reference = com.example.kido.media.MediaFiles.baseName(item.getFileName());
            referenceYear = item.getYear();
        }

        List<MatchCandidate> scored = new ArrayList<>(
                candidates.candidatesFor(item, query, capped * 2));
        for (MatchCandidate candidate : scored) {
            candidate.setMatchPercent(TitleSimilarity.score(
                    reference, referenceYear, candidate.getTitle(), candidate.getYear()));
        }

        List<MatchCandidate> useful = scored.stream()
                .filter(MatchFixService::worthOffering)
                .sorted(Comparator.comparingInt(MatchCandidate::getMatchPercent).reversed())
                .limit(capped)
                .toList();

        return useful.stream().map(CandidateDto::from).toList();
    }

    /**
     * Applies the owner's answer and locks it against future rescans.
     *
     * <p>Only the descriptive fields change. The file path, size and probe results are
     * facts about the bytes and stay exactly as they are.
     */
    @Transactional
    public FixResultDto apply(String itemId, ApplyMatchRequest request) {
        MediaItem item = require(itemId);

        MatchCandidate chosen = resolveChoice(item, request);
        String title = chosen != null ? chosen.getTitle() : request.title();
        if (title == null || title.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Provide either a candidateId or a title");
        }

        item.setTitle(title.trim());
        item.setSortTitle(FilenameParser.sortTitle(title.trim()));
        item.setYear(chosen != null && chosen.getYear() != null ? chosen.getYear() : request.year());

        if (chosen != null) {
            // A candidate carrying real metadata brings it along; a bare title does not
            // wipe what is already there.
            applyIfPresent(chosen.getPlot(), item::setPlot);
            applyIfPresent(chosen.getCertification(), item::setCertification);
            applyIfPresent(chosen.getStudio(), item::setStudio);
            applyIfPresent(chosen.getDirectors(), item::setDirectors);
            applyIfPresent(chosen.getCastMembers(), item::setCastMembers);
            applyIfPresent(chosen.getTmdbId(), item::setTmdbId);
            applyIfPresent(chosen.getImdbId(), item::setImdbId);
            if (chosen.getRuntimeMinutes() != null) {
                item.setRuntimeMinutes(chosen.getRuntimeMinutes());
            }
            if (chosen.getRating() != null) {
                item.setRating(chosen.getRating());
            }
            if (chosen.getGenres() != null && !chosen.getGenres().isEmpty()) {
                item.getGenres().clear();
                item.getGenres().addAll(chosen.getGenres());
            }
        }

        // The lock is the whole point: without it the next rescan re-guesses the title.
        item.setMetadataSource(MetadataSource.MANUAL);
        item.setHidden(false);
        item.setUpdatedAt(Instant.now());
        MediaItem saved = items.save(item);

        log.info("Metadata corrected for {}: '{}' ({})",
                saved.getId(), saved.getTitle(), saved.getYear());

        return toResult(saved, "Saved. Nothing on disk was changed.");
    }

    /**
     * Moves an item to another category — the "it's a home video, not a film" escape
     * hatch — and locks the type so a rescan cannot put it back.
     */
    @Transactional
    public FixResultDto reclassify(String itemId, String rawType) {
        MediaItem item = require(itemId);
        MediaType type = MediaType.parse(rawType).orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST, "Unknown category '" + rawType + "'"));

        MediaType previous = item.getType();
        item.setType(type);
        item.setTypeLocked(true);
        item.setUpdatedAt(Instant.now());

        // Sprite sheets are still valid — same bytes — but a photo has no use for them.
        if (previous.isVideo() && !type.isVideo()) {
            trickplay.discard(itemId);
        }
        MediaItem saved = items.save(item);

        log.info("Reclassified {} from {} to {}", itemId, previous, type);
        return toResult(saved, "Moved to " + type.label()
                + ". The file itself has not moved on disk.");
    }

    /**
     * Leaves an item unmatched and out of browsing.
     *
     * <p>The row is kept rather than deleted: the file is still on disk, still scanned,
     * and the owner may change their mind. Hiding also takes it out of this queue, since
     * "leave it alone" is an answer.
     */
    @Transactional
    public FixResultDto unmatch(String itemId) {
        MediaItem item = require(itemId);
        item.setHidden(true);
        // MANUAL so a rescan does not resurrect it into the queue with a fresh guess.
        item.setMetadataSource(MetadataSource.MANUAL);
        item.setUpdatedAt(Instant.now());
        MediaItem saved = items.save(item);

        log.info("Left {} unmatched and hidden", itemId);
        return toResult(saved, "Hidden from the library. The file is untouched on disk.");
    }

    /** Undoes a manual correction and lets the scanner have another go. */
    @Transactional
    public FixResultDto reset(String itemId) {
        MediaItem item = require(itemId);
        item.setMetadataSource(MetadataSource.FILENAME);
        item.setTypeLocked(false);
        item.setHidden(false);
        item.setUpdatedAt(Instant.now());
        MediaItem saved = items.save(item);

        return toResult(saved,
                "Correction removed. The next library scan will read this file again.");
    }

    /**
     * Whether a candidate is shown at all.
     *
     * <p>The relevance floor applies only to suggestions drawn from the wider library,
     * which can run to hundreds of mostly irrelevant titles. Everything found next to
     * the file — a sidecar, the folder name, a re-reading of the filename — is always
     * offered, and so is anything typed.
     *
     * <p>This distinction is the difference between the screen working and not. Scoring
     * is done against the filename when there is no query, and a mangled filename
     * scores badly against the correct title by definition — so a blanket floor would
     * hide the right answer in exactly the case this screen exists to fix.
     */
    private static boolean worthOffering(MatchCandidate candidate) {
        if (!"library".equals(candidate.getOrigin())) {
            return true;
        }
        return candidate.getMatchPercent() >= MIN_USEFUL_MATCH;
    }

    /** Resolves a posted candidate id by regenerating the same candidate list. */
    private MatchCandidate resolveChoice(MediaItem item, ApplyMatchRequest request) {
        if (request.candidateId() == null || request.candidateId().isBlank()) {
            return null;
        }
        // Candidate ids are positional within a response, so the list is rebuilt with
        // the same inputs. Stateless by design: caching per-owner candidate lists would
        // add a cache to expire for no benefit on a screen used a handful of times.
        Optional<MatchCandidate> match = candidates
                .candidatesFor(item, request.title(), 100).stream()
                .filter(candidate -> candidate.getId().equals(request.candidateId()))
                .findFirst();

        if (match.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "That suggestion is no longer available — reload the candidates and "
                            + "choose again");
        }
        return match.get();
    }

    private static void applyIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    /** Guessed metadata, still present on disk, not already answered for. */
    private static Specification<MediaItem> suspected() {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("metadataSource"), MetadataSource.FILENAME),
                cb.isFalse(root.get("missing")),
                cb.isFalse(root.get("hidden")));
    }

    private MediaItem require(String itemId) {
        return items.findById(itemId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    private static MismatchDto toMismatch(MediaItem item) {
        return new MismatchDto(
                item.getId(),
                item.getType().name(),
                item.getTitle(),
                item.getYear(),
                item.getFileName(),
                item.getFolderPath(),
                item.getMetadataSource() == null ? null : item.getMetadataSource().name(),
                item.getFileSize(),
                item.hasPoster());
    }

    private FixResultDto toResult(MediaItem item, String detail) {
        return new FixResultDto(
                item.getId(),
                item.getTitle(),
                item.getYear(),
                item.getType().name(),
                item.getMetadataSource() == null ? null : item.getMetadataSource().name(),
                item.isTypeLocked(),
                item.isHidden(),
                detail,
                remaining());
    }
}

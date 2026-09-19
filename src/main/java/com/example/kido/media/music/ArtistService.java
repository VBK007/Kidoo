package com.example.kido.media.music;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.dto.ArtistDtos.ArtistDetailDto;
import com.example.kido.media.dto.ArtistDtos.ArtistPageDto;
import com.example.kido.media.dto.ArtistDtos.ArtistSummaryDto;
import com.example.kido.profile.Profile;

/**
 * The artists grid and an artist's own catalog page. See {@link
 * com.example.kido.media.dto.ArtistDtos}.
 */
@Service
public class ArtistService {

    private static final List<MediaType> MUSIC_TYPES = List.of(MediaType.MUSIC, MediaType.VIDEO_SONG);

    /** Tracks fetched to pick a tile's representative image from — more than one so a
     * poster-less track (which is common — see AudioFeatureService's own decode-failure
     * rate) does not leave an artist with no image when a later track of theirs has one. */
    private static final int REPRESENTATIVE_CANDIDATE_POOL = 5;

    private static final int MAX_TRACKS_PER_ARTIST = 500;

    private final MediaItemRepository items;
    private final CatalogService catalog;

    public ArtistService(MediaItemRepository items, CatalogService catalog) {
        this.items = items;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public ArtistPageDto list(int page, int size) {
        int pageSize = Math.min(Math.max(1, size), 100);
        int pageIndex = Math.max(0, page);

        List<Object[]> all = items.countByArtistName(MUSIC_TYPES);
        int from = Math.min(pageIndex * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());

        List<ArtistSummaryDto> artists = all.subList(from, to).stream()
                .map(row -> toSummary((String) row[0], ((Number) row[1]).longValue()))
                .toList();

        int totalPages = (int) Math.ceil(all.size() / (double) pageSize);
        return new ArtistPageDto(artists, pageIndex, pageSize, all.size(), totalPages);
    }

    /** @throws ApiException 404 if no track in the library credits this artist */
    @Transactional(readOnly = true)
    public ArtistDetailDto detail(Profile profile, String name, int limit) {
        List<MediaItem> tracks = items.findByArtistName(
                MUSIC_TYPES, name, PageRequest.of(0, Math.min(Math.max(1, limit), MAX_TRACKS_PER_ARTIST)));
        if (tracks.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No artist named '" + name + "'");
        }
        long total = items.countByArtistNameExact(MUSIC_TYPES, name);
        return new ArtistDetailDto(name, representativeItemId(tracks), total, catalog.summarise(profile, tracks));
    }

    private ArtistSummaryDto toSummary(String name, long trackCount) {
        List<MediaItem> candidates =
                items.findByArtistName(MUSIC_TYPES, name, PageRequest.of(0, REPRESENTATIVE_CANDIDATE_POOL));
        return new ArtistSummaryDto(name, representativeItemId(candidates), trackCount);
    }

    private static String representativeItemId(List<MediaItem> candidates) {
        Optional<MediaItem> withPoster = candidates.stream().filter(MediaItem::hasPoster).findFirst();
        return withPoster.or(() -> candidates.stream().findFirst()).map(MediaItem::getId).orElse(null);
    }
}

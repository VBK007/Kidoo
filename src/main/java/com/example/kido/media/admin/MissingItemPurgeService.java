package com.example.kido.media.admin;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.downloads.DownloadJobRepository;
import com.example.kido.media.engagement.MediaItemCommentRepository;
import com.example.kido.media.engagement.MediaItemLikeRepository;
import com.example.kido.media.playback.PlaybackProgressRepository;
import com.example.kido.media.probe.MediaChapterRepository;
import com.example.kido.media.session.WatchEventRepository;
import com.example.kido.media.trickplay.TrickplayService;

import lombok.extern.slf4j.Slf4j;

/**
 * Deletes rows for items a scan could no longer find on disk.
 *
 * <p>A missing row is kept rather than deleted at scan time on purpose — a disk being
 * temporarily unmounted must not wipe out watch progress. This is the other half of
 * that design: an explicit, owner-triggered action for when "missing" really does mean
 * gone for good, whether that is a file actually deleted off disk or a row that should
 * never have been indexed as its own catalog entry in the first place (an image sitting
 * in a shared posters folder, say, from before the scanner learned to skip those).
 *
 * <p>Every table that references the item is cleaned up explicitly. {@code
 * media_item_genres} and {@code media_item_people} are {@code @ElementCollection}s with
 * a real foreign key, so deleting the entity through the repository cascades those two
 * automatically; nothing else in the schema has a matching constraint, so a like,
 * comment, watch progress row, watch event, download job or trickplay manifest left
 * behind would simply sit there forever, unreachable but never cleaned up, if this
 * service did not do it by hand.
 */
@Slf4j
@Service
public class MissingItemPurgeService {

    private final MediaItemRepository items;
    private final MediaChapterRepository chapters;
    private final MediaItemLikeRepository likes;
    private final MediaItemCommentRepository comments;
    private final PlaybackProgressRepository progress;
    private final WatchEventRepository watchEvents;
    private final DownloadJobRepository downloadJobs;
    private final TrickplayService trickplay;

    public MissingItemPurgeService(MediaItemRepository items,
                                   MediaChapterRepository chapters,
                                   MediaItemLikeRepository likes,
                                   MediaItemCommentRepository comments,
                                   PlaybackProgressRepository progress,
                                   WatchEventRepository watchEvents,
                                   DownloadJobRepository downloadJobs,
                                   TrickplayService trickplay) {
        this.items = items;
        this.chapters = chapters;
        this.likes = likes;
        this.comments = comments;
        this.progress = progress;
        this.watchEvents = watchEvents;
        this.downloadJobs = downloadJobs;
        this.trickplay = trickplay;
    }

    /** @return how many rows were removed */
    @Transactional
    public int purge() {
        List<MediaItem> missing = items.findByMissingTrue();
        for (MediaItem item : missing) {
            String id = item.getId();
            chapters.deleteByMediaItemId(id);
            likes.deleteByMediaItemId(id);
            comments.deleteByMediaItemId(id);
            progress.deleteByMediaItemId(id);
            watchEvents.deleteByMediaItemId(id);
            downloadJobs.deleteByMediaItemId(id);
            trickplay.discard(id);
        }
        items.deleteAll(missing);

        if (!missing.isEmpty()) {
            log.info("Purged {} missing items", missing.size());
        }
        return missing.size();
    }
}

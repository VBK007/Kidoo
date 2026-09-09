package com.example.kido.media.downloads;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DownloadJobRepository extends JpaRepository<DownloadJob, String> {

    /**
     * The Saved list, paged. Unpaged would grow without limit: cancelled and expired
     * jobs stay as history, so a long-lived profile accumulates them indefinitely.
     */
    Page<DownloadJob> findByProfileId(String profileId, Pageable pageable);

    /** Bytes of fetchable copies, for the list header, across every page. */
    @Query("""
            select coalesce(sum(j.fileSize), 0) from DownloadJob j
            where j.profileId = :profileId and j.state = :state
            """)
    long sumFileSizeByProfileIdAndState(@Param("profileId") String profileId,
                                        @Param("state") DownloadJob.State state);

    Optional<DownloadJob> findByIdAndProfileId(String id, String profileId);

    long countByProfileIdAndStateIn(String profileId, List<DownloadJob.State> states);

    Optional<DownloadJob> findFirstByProfileIdAndMediaItemIdAndStateIn(
            String profileId, String mediaItemId, List<DownloadJob.State> states);

    List<DownloadJob> findByStateIn(List<DownloadJob.State> states);

    /** Oldest queued job first — the worker takes them in order. */
    Optional<DownloadJob> findFirstByStateOrderByCreatedAtAsc(DownloadJob.State state);

    @Query("""
            select j from DownloadJob j
            where j.state = :state and j.expiresAt is not null and j.expiresAt < :now
            """)
    List<DownloadJob> findExpired(@Param("state") DownloadJob.State state,
                                  @Param("now") Instant now);

    @Query("""
            select coalesce(sum(j.fileSize), 0) from DownloadJob j
            where j.state = :state
            """)
    long totalBytesInState(@Param("state") DownloadJob.State state);
}

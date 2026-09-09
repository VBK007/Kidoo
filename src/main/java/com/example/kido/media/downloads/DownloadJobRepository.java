package com.example.kido.media.downloads;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DownloadJobRepository extends JpaRepository<DownloadJob, String> {

    List<DownloadJob> findByProfileIdOrderByCreatedAtDesc(String profileId);

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

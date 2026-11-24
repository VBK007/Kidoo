package com.example.kido.media.engagement.mirror;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MirrorCommentRepository extends JpaRepository<MirrorComment, String> {

    /**
     * Newest first: a mirror thread is read as a feed, not as a transcript.
     *
     * <p>The id breaks ties, and it has to, for the same reason the catalogue thread's
     * finder does. {@code createdAt} is not unique: the clock behind {@code Instant.now()}
     * advances in ticks — on Windows roughly every 15ms, during which every call returns
     * the same value — so two comments posted in the same breath carry the same timestamp.
     * Without a second, fixed column the order within such a group is whatever the database
     * feels like and need not be the same twice, which on separate page queries can show
     * one comment on two pages and drop another. The id is a random UUID, so it orders
     * arbitrarily but stably, which is all paging needs.
     */
    Page<MirrorComment> findByMirrorIdOrderByCreatedAtDescIdDesc(String mirrorId, Pageable pageable);

    long countByMirrorId(String mirrorId);
}

package com.example.kido.media.probe;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaChapterRepository extends JpaRepository<MediaChapter, String> {

    List<MediaChapter> findByMediaItemIdOrderByChapterIndexAsc(String mediaItemId);

    void deleteByMediaItemId(String mediaItemId);

    boolean existsByMediaItemId(String mediaItemId);
}

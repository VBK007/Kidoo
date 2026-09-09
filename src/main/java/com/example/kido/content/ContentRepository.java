package com.example.kido.content;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContentRepository extends JpaRepository<ContentItem, String> {

    List<ContentItem> findByPublishedTrueAndVersionGreaterThan(long since);

    List<ContentItem> findByTypeAndPublishedTrue(String type);

    Optional<ContentItem> findByTypeAndKey(String type, String key);

    @Query("select coalesce(max(c.version), 0) from ContentItem c")
    long maxVersion();
}

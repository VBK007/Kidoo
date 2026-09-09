package com.example.kido.media.trickplay;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TrickplayManifestRepository extends JpaRepository<TrickplayManifest, String> {

    Optional<TrickplayManifest> findByMediaItemId(String mediaItemId);

    List<TrickplayManifest> findByState(TrickplayManifest.State state);

    void deleteByMediaItemId(String mediaItemId);
}

package com.example.kido.poster;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PosterComponentRepository extends JpaRepository<PosterComponent, String> {

    List<PosterComponent> findAllByOrderByTypeAscNameAsc();

    List<PosterComponent> findByTypeOrderByNameAsc(PosterComponentType type);

    Optional<PosterComponent> findByTypeAndName(PosterComponentType type, String name);
}

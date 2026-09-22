package com.example.kido.poster;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PosterComponentRepository extends JpaRepository<PosterComponent, String> {

    List<PosterComponent> findAllByOrderByTypeAscNameAsc();

    List<PosterComponent> findByTypeOrderByNameAsc(PosterComponentType type);

    Optional<PosterComponent> findByTypeAndName(PosterComponentType type, String name);

    /**
     * Fonts, stickers and frames tallied for the admin dashboard.
     *
     * @return {@code [type, count]} rows
     */
    @Query("select c.type, count(c) from PosterComponent c group by c.type")
    List<Object[]> countByComponentType();
}

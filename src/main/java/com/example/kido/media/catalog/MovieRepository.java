package com.example.kido.media.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * Browsing uses {@link JpaSpecificationExecutor} rather than derived queries: the
 * catalog filters are optional and combinable, and binding an unused {@code null}
 * parameter into a JPQL {@code :x is null} guard makes PostgreSQL fail to infer the
 * parameter type. Criteria queries simply omit the predicate instead.
 */
public interface MovieRepository extends JpaRepository<Movie, String>, JpaSpecificationExecutor<Movie> {

    Optional<Movie> findByFilePath(String filePath);

    List<Movie> findByMissingFalse();

    long countByMissingFalse();

    @Query("select distinct g from Movie m join m.genres g where m.missing = false order by g")
    List<String> findDistinctGenres();

    @Query("select m from Movie m where m.missing = false and m.mediaInfo.probedAt is null")
    List<Movie> findUnprobed();
}

package com.example.kido.poster;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Paged throughout: the seeded catalog alone runs to four figures, so there is no
 * caller that wants every template at once. The exception is
 * {@link PosterComponentService}, which reads them all to find out whether a component
 * is still referenced — that one has to see the whole catalog to answer at all.
 */
public interface PosterTemplateRepository extends JpaRepository<PosterTemplate, String> {

    Page<PosterTemplate> findByPublishedTrue(Pageable pageable);

    Page<PosterTemplate> findByCategoryAndPublishedTrue(PosterCategory category, Pageable pageable);

    Page<PosterTemplate> findByCategory(PosterCategory category, Pageable pageable);

    // --- admin dashboard ---

    long countByPublishedTrue();

    /**
     * One row per ceremony that has any template at all.
     *
     * <p>An aggregate rather than seven counts, and certainly not a load: the seeded
     * catalog is four figures of templates each carrying a whole layout.
     *
     * @return {@code [category, count]} rows
     */
    @Query("select t.category, count(t) from PosterTemplate t group by t.category")
    List<Object[]> countByCategory();
}

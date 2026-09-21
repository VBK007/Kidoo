package com.example.kido.poster;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}

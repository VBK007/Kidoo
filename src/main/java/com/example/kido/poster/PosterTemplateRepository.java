package com.example.kido.poster;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PosterTemplateRepository extends JpaRepository<PosterTemplate, String> {

    List<PosterTemplate> findByPublishedTrueOrderByCategoryAscSortOrderAscNameAsc();

    List<PosterTemplate> findByCategoryAndPublishedTrueOrderBySortOrderAscNameAsc(PosterCategory category);

    List<PosterTemplate> findAllByOrderByCategoryAscSortOrderAscNameAsc();

    List<PosterTemplate> findByCategoryOrderBySortOrderAscNameAsc(PosterCategory category);

    boolean existsByCategoryAndName(PosterCategory category, String name);
}

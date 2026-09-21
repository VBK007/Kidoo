package com.example.kido.poster;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.poster.dto.PosterDtos.TemplateDto;
import com.example.kido.poster.dto.PosterDtos.TemplatePageDto;
import com.example.kido.poster.dto.PosterDtos.TemplateRequest;
import com.example.kido.poster.dto.PosterDtos.TemplateSummaryDto;

import lombok.extern.slf4j.Slf4j;

/**
 * Ceremony poster templates: the designs a card is started from.
 *
 * <p>Reads default to published rows only. {@code includeDrafts} exists for the person
 * authoring a set — it is the admin's own view, and the controller only offers it on
 * the endpoints that already require the admin key.
 *
 * <p>Listings are paged, because the seeded catalog alone is four figures. The page
 * size is capped rather than trusted: a thousand full layouts in one response is tens
 * of megabytes, which is not a request this server should honour however politely it
 * is asked.
 */
@Slf4j
@Service
public class PosterTemplateService {

    /** Matches the catalog's cap, which the client already knows about. */
    private static final int MAX_PAGE_SIZE = 100;

    private final PosterTemplateRepository templates;
    private final PosterComponentService components;

    public PosterTemplateService(PosterTemplateRepository templates, PosterComponentService components) {
        this.templates = templates;
        this.components = components;
    }

    @Transactional(readOnly = true)
    public TemplatePageDto<?> list(boolean includeDrafts, int page, int size, boolean summary) {
        Pageable pageable = pageable(page, size);
        Page<PosterTemplate> rows = includeDrafts
                ? templates.findAll(pageable)
                : templates.findByPublishedTrue(pageable);
        return envelope(rows, summary);
    }

    /**
     * @param rawCategory as it arrived in the URL: {@code marriage},
     *                    {@code baby-shower}, {@code BABY_SHOWER}
     */
    @Transactional(readOnly = true)
    public TemplatePageDto<?> byCategory(String rawCategory, boolean includeDrafts,
                                         int page, int size, boolean summary) {
        PosterCategory category = PosterCategory.parse(rawCategory);
        Pageable pageable = pageable(page, size);
        Page<PosterTemplate> rows = includeDrafts
                ? templates.findByCategory(category, pageable)
                : templates.findByCategoryAndPublishedTrue(category, pageable);
        return envelope(rows, summary);
    }

    @Transactional(readOnly = true)
    public TemplateDto get(String id) {
        return TemplateDto.from(templates.findById(id).orElseThrow(() -> notFound(id)));
    }

    @Transactional
    public TemplateDto create(TemplateRequest request) {
        PosterCategory category = PosterCategory.parse(request.category());
        PosterLayout layout = request.layout().normalised();
        components.requireReferencesResolve(layout);

        PosterTemplate saved = templates.save(PosterTemplate.builder()
                .category(category)
                .name(request.name().trim())
                .thumbnail(request.thumbnail())
                .layout(layout)
                .colorThemes(themesOrDefault(request, layout))
                .published(request.published() == null || request.published())
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .build());
        log.info("Created poster template '{}' in {} ({})", saved.getName(), category, saved.getId());
        return TemplateDto.from(saved);
    }

    /**
     * Replaces a template whole.
     *
     * <p>Not a patch: a layout only means anything entire. A request that changed the
     * text boxes and left last week's sticker placements behind would save a design
     * nobody ever drew.
     */
    @Transactional
    public TemplateDto update(String id, TemplateRequest request) {
        PosterTemplate template = templates.findById(id).orElseThrow(() -> notFound(id));
        PosterCategory category = PosterCategory.parse(request.category());
        PosterLayout layout = request.layout().normalised();
        components.requireReferencesResolve(layout);

        template.setCategory(category);
        template.setName(request.name().trim());
        template.setThumbnail(request.thumbnail());
        template.setLayout(layout);
        template.setColorThemes(themesOrDefault(request, layout));
        template.setPublished(request.published() == null || request.published());
        template.setSortOrder(request.sortOrder() == null ? template.getSortOrder() : request.sortOrder());
        template.setUpdatedAt(Instant.now());
        return TemplateDto.from(templates.save(template));
    }

    @Transactional
    public void delete(String id) {
        PosterTemplate template = templates.findById(id).orElseThrow(() -> notFound(id));
        templates.delete(template);
        log.info("Deleted poster template '{}' ({})", template.getName(), id);
    }

    /**
     * A template always has at least one palette.
     *
     * <p>The editor draws a row of swatches and recolours on a tap; with no themes at
     * all that row is empty and the feature looks broken rather than unconfigured. So
     * a template that ships none is given its own colours as "Default" — which is
     * exactly what the design already looks like.
     */
    private List<ColorTheme> themesOrDefault(TemplateRequest request, PosterLayout layout) {
        if (request.colorThemes() != null && !request.colorThemes().isEmpty()) {
            return new ArrayList<>(request.colorThemes());
        }
        String primary = layout.textBoxes().stream()
                .map(PosterLayout.TextBox::color)
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse("#000000");
        return new ArrayList<>(List.of(new ColorTheme("Default", primary, layout.backgroundColor())));
    }

    /**
     * Same ordering for every listing: the ceremony, then the order a designer gave
     * within it, then the name, so a shelf never reshuffles between two requests for
     * two pages of it.
     */
    private Pageable pageable(int page, int size) {
        return PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                Sort.by("category").ascending()
                        .and(Sort.by("sortOrder").ascending())
                        .and(Sort.by("name").ascending()));
    }

    private TemplatePageDto<?> envelope(Page<PosterTemplate> rows, boolean summary) {
        List<?> items = summary
                ? rows.getContent().stream().map(TemplateSummaryDto::from).toList()
                : rows.getContent().stream().map(TemplateDto::from).toList();
        return new TemplatePageDto<>(items, rows.getNumber(), rows.getSize(),
                rows.getTotalElements(), rows.getTotalPages());
    }

    private ApiException notFound(String id) {
        return new ApiException(HttpStatus.NOT_FOUND, "No poster template with id " + id);
    }
}

package com.example.kido.poster.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.kido.poster.ColorTheme;
import com.example.kido.poster.PosterComponent;
import com.example.kido.poster.PosterLayout;
import com.example.kido.poster.PosterTemplate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response shapes for the ceremony poster module. */
public final class PosterDtos {
    private PosterDtos() {}

    /**
     * A template as the editor receives it.
     *
     * <p>{@code category} is the enum name and {@code categorySlug} the form that goes
     * back in a URL, so a client never has to lower-case and hyphenate a value itself
     * to build the next request.
     *
     * <p>The layout carries component <i>ids</i>, not the components themselves: the
     * same font and the same mandala appear across most of a category, and inlining
     * them would send the same bytes a dozen times in one response. Fetch the catalog
     * once from {@code GET /api/poster/components} and resolve against it.
     */
    public record TemplateDto(
            String id,
            String category,
            String categorySlug,
            String name,
            String thumbnail,
            PosterLayout layout,
            List<ColorTheme> colorThemes,
            boolean published,
            int sortOrder,
            Instant updatedAt
    ) {
        public static TemplateDto from(PosterTemplate t) {
            return new TemplateDto(
                    t.getId(),
                    t.getCategory().name(),
                    t.getCategory().slug(),
                    t.getName(),
                    t.getThumbnail(),
                    t.getLayout() == null ? null : t.getLayout().normalised(),
                    t.getColorThemes() == null ? List.of() : List.copyOf(t.getColorThemes()),
                    t.isPublished(),
                    t.getSortOrder(),
                    t.getUpdatedAt());
        }
    }

    /**
     * A template without its layout, for the picker grid.
     *
     * <p>The grid draws a thumbnail, a name and a swatch row; the layout behind it is
     * the bulk of the response and none of it is on screen. At a catalog of a thousand
     * that is the difference between a page of 40 costing tens of kilobytes and costing
     * most of a megabyte. Ask for the whole thing with {@code /by-id/{id}} when
     * somebody opens one.
     */
    public record TemplateSummaryDto(
            String id,
            String category,
            String categorySlug,
            String name,
            String thumbnail,
            List<ColorTheme> colorThemes,
            boolean published,
            int sortOrder,
            Instant updatedAt
    ) {
        public static TemplateSummaryDto from(PosterTemplate t) {
            return new TemplateSummaryDto(
                    t.getId(),
                    t.getCategory().name(),
                    t.getCategory().slug(),
                    t.getName(),
                    t.getThumbnail(),
                    t.getColorThemes() == null ? List.of() : List.copyOf(t.getColorThemes()),
                    t.isPublished(),
                    t.getSortOrder(),
                    t.getUpdatedAt());
        }
    }

    /**
     * A page of templates, in the envelope the rest of this server uses.
     *
     * <p>Generic in what it holds because the same page is served two ways: full
     * templates by default, and {@link TemplateSummaryDto} when the client asks for
     * {@code view=summary}.
     *
     * @see com.example.kido.media.dto.CatalogDtos.ItemPageDto
     */
    public record TemplatePageDto<T>(
            List<T> items,
            int page,
            int size,
            long totalItems,
            int totalPages
    ) {}

    /**
     * Creates a template, and replaces one whole on {@code PUT}.
     *
     * <p>{@code PUT} is a replacement rather than a patch because a layout only makes
     * sense entire: a request that changed the text boxes and left last week's sticker
     * placements behind would save a design nobody drew.
     *
     * @param category one of {@code PosterCategory} — the enum name, the hyphenated
     *                 form or the spoken one
     * @param published defaults to true when omitted; send false to draft a template
     *                  that no phone will list
     */
    public record TemplateRequest(
            @NotBlank String category,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 512) String thumbnail,
            @NotNull @Valid PosterLayout layout,
            @Size(max = 12) List<@Valid ColorTheme> colorThemes,
            Boolean published,
            Integer sortOrder
    ) {}

    /** A font, sticker or frame as the editor receives it. */
    public record ComponentDto(
            String id,
            String type,
            String name,
            String url,
            Map<String, Object> properties,
            Instant updatedAt
    ) {
        public static ComponentDto from(PosterComponent c) {
            return new ComponentDto(
                    c.getId(),
                    c.getType().name(),
                    c.getName(),
                    c.getUrl(),
                    c.getProperties() == null ? Map.of() : Map.copyOf(c.getProperties()),
                    c.getUpdatedAt());
        }
    }

    /**
     * Creates a component, and replaces one whole on {@code PUT}.
     *
     * @param type one of {@code PosterComponentType}
     * @param url  required for a font and a sticker, which are files; optional on a
     *             frame, which may be nothing but {@code properties}
     */
    public record ComponentRequest(
            @NotBlank String type,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 512) String url,
            Map<String, Object> properties
    ) {}
}

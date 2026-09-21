package com.example.kido.poster;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.poster.dto.PosterDtos.ComponentDto;
import com.example.kido.poster.dto.PosterDtos.ComponentRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * The catalog of fonts, stickers and frames that templates are built out of.
 *
 * <p>Deletes are refused while a template still points at the component. The
 * alternative — letting the row go and leaving the reference dangling — turns a
 * template that validated when it was saved into a poster that renders with a gap in
 * it, and nothing afterwards would say which delete caused it.
 */
@Slf4j
@Service
public class PosterComponentService {

    private final PosterComponentRepository components;
    private final PosterTemplateRepository templates;

    public PosterComponentService(PosterComponentRepository components,
                                  PosterTemplateRepository templates) {
        this.components = components;
        this.templates = templates;
    }

    @Transactional(readOnly = true)
    public List<ComponentDto> list() {
        return components.findAllByOrderByTypeAscNameAsc().stream().map(ComponentDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ComponentDto> byType(String rawType) {
        return components.findByTypeOrderByNameAsc(PosterComponentType.parse(rawType)).stream()
                .map(ComponentDto::from).toList();
    }

    @Transactional
    public ComponentDto create(ComponentRequest request) {
        PosterComponentType type = PosterComponentType.parse(request.type());
        String name = request.name().trim();
        requireUrlWhereItMatters(type, request.url());
        if (components.findByTypeAndName(type, name).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A " + type + " named '" + name + "' already exists");
        }
        PosterComponent saved = components.save(PosterComponent.builder()
                .type(type)
                .name(name)
                .url(trimToNull(request.url()))
                .properties(copyProperties(request.properties()))
                .build());
        log.info("Created poster component {} '{}' ({})", type, name, saved.getId());
        return ComponentDto.from(saved);
    }

    /** Replaces a component whole; its id, and so every reference to it, survives. */
    @Transactional
    public ComponentDto update(String id, ComponentRequest request) {
        PosterComponent component = components.findById(id).orElseThrow(() -> notFound(id));
        PosterComponentType type = PosterComponentType.parse(request.type());
        String name = request.name().trim();
        requireUrlWhereItMatters(type, request.url());

        Optional<PosterComponent> clash = components.findByTypeAndName(type, name);
        if (clash.isPresent() && !clash.get().getId().equals(id)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A " + type + " named '" + name + "' already exists");
        }
        // A template that asked for a sticker and would now be handed a font is the
        // same broken render as a dangling id, so a type change waits until nothing
        // points here.
        if (component.getType() != type) {
            requireUnreferenced(component, "change the type of");
        }

        component.setType(type);
        component.setName(name);
        component.setUrl(trimToNull(request.url()));
        component.setProperties(copyProperties(request.properties()));
        component.setUpdatedAt(Instant.now());
        return ComponentDto.from(components.save(component));
    }

    @Transactional
    public void delete(String id) {
        PosterComponent component = components.findById(id).orElseThrow(() -> notFound(id));
        requireUnreferenced(component, "delete");
        components.delete(component);
        log.info("Deleted poster component {} '{}' ({})", component.getType(), component.getName(), id);
    }

    /**
     * Resolves the component ids a layout names, refusing anything missing or of the
     * wrong kind.
     *
     * <p>Called by {@link PosterTemplateService} on every save, in one query: the point
     * is that a template cannot reach the database describing a design that cannot be
     * drawn.
     */
    @Transactional(readOnly = true)
    void requireReferencesResolve(PosterLayout layout) {
        Map<String, PosterComponentType> wanted = PosterReferences.of(layout);
        if (wanted.isEmpty()) {
            return;
        }
        Map<String, PosterComponentType> actual = new LinkedHashMap<>();
        components.findAllById(wanted.keySet()).forEach(c -> actual.put(c.getId(), c.getType()));

        for (Map.Entry<String, PosterComponentType> ref : wanted.entrySet()) {
            PosterComponentType found = actual.get(ref.getKey());
            if (found == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Unknown " + ref.getValue() + " component '" + ref.getKey() + "'");
            }
            if (found != ref.getValue()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Component '" + ref.getKey() + "' is a " + found
                                + " but the layout uses it as a " + ref.getValue());
            }
        }
    }

    private void requireUnreferenced(PosterComponent component, String verb) {
        List<String> users = templates.findAll().stream()
                .filter(t -> PosterReferences.of(t.getLayout()).containsKey(component.getId()))
                .map(PosterTemplate::getName)
                .sorted()
                .toList();
        if (!users.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot " + verb + " '" + component.getName() + "': "
                            + users.size() + " template(s) still use it — " + String.join(", ", users));
        }
    }

    /**
     * A font and a sticker are files, so a row without a URL is a component that draws
     * nothing. A frame is a way of stroking a border, and may be properties alone.
     */
    private void requireUrlWhereItMatters(PosterComponentType type, String url) {
        if (type != PosterComponentType.FRAME && trimToNull(url) == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A " + type + " needs a url");
        }
    }

    private Map<String, Object> copyProperties(Map<String, Object> properties) {
        return properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ApiException notFound(String id) {
        return new ApiException(HttpStatus.NOT_FOUND, "No poster component with id " + id);
    }
}

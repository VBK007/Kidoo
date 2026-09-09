package com.example.kido.media.metadata;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads a Kodi/Radarr-style {@code movie.nfo} sidecar.
 *
 * <p>These files are XML written by other tools, so the parser is locked down against
 * XXE: DOCTYPE declarations are rejected outright and every form of external entity
 * resolution is disabled. Without that, a crafted {@code .nfo} dropped into a watched
 * folder would be enough to make the server read local files or open outbound
 * connections during a scan.
 *
 * <p>Sidecars in the wild are frequently malformed or carry a plain-text preamble
 * before the root element, so a parse failure is logged and treated as "no sidecar"
 * rather than failing the surrounding scan.
 */
@Slf4j
@Component
public class NfoParser {

    /** Larger than this is not a plausible metadata sidecar. */
    private static final long MAX_NFO_BYTES = 2L * 1024 * 1024;

    /** @return parsed metadata, or empty if there is no readable movie document */
    public Optional<SidecarMetadata> parse(Path nfoFile) {
        try {
            if (!Files.isRegularFile(nfoFile) || Files.size(nfoFile) > MAX_NFO_BYTES) {
                return Optional.empty();
            }
            Document doc = parseDocument(Files.readAllBytes(nfoFile));
            if (doc == null) {
                return Optional.empty();
            }
            Element root = doc.getDocumentElement();
            if (root == null) {
                return Optional.empty();
            }
            // Some writers wrap the movie element in a container; accept either shape.
            Element movie = "movie".equalsIgnoreCase(root.getTagName())
                    ? root
                    : firstElement(root, "movie");
            return movie == null ? Optional.empty() : Optional.of(toMetadata(movie));
        } catch (Exception ex) {
            log.debug("Unreadable .nfo at {}: {}", nfoFile, ex.getMessage());
            return Optional.empty();
        }
    }

    private Document parseDocument(byte[] raw) {
        // Strip a UTF-8 BOM and any junk before the first tag. Both are common in
        // scene-released .nfo files and both make a strict XML parser reject everything.
        int start = 0;
        if (raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        while (start < raw.length && raw[start] != 0x3C) {
            start++;
        }
        if (start >= raw.length) {
            return null;
        }
        try {
            return secureBuilder().parse(new ByteArrayInputStream(raw, start, raw.length - start));
        } catch (Exception ex) {
            log.debug("XML parse failed for .nfo: {}", ex.getMessage());
            return null;
        }
    }

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // The decisive one: with no DOCTYPE accepted there is no entity to expand.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        // Belt and braces: resolve any DTD reference to nothing rather than fetching it.
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder;
    }

    private SidecarMetadata toMetadata(Element movie) {
        String title = firstNonBlank(text(movie, "title"), text(movie, "localtitle"));
        return new SidecarMetadata(
                title,
                text(movie, "originaltitle"),
                text(movie, "sorttitle"),
                year(movie),
                firstNonBlank(text(movie, "plot"), text(movie, "outline"), text(movie, "summary")),
                text(movie, "tagline"),
                runtimeMinutes(movie),
                rating(movie),
                firstNonBlank(text(movie, "mpaa"), text(movie, "certification")),
                genres(movie),
                texts(movie, "director"),
                actors(movie),
                text(movie, "studio"),
                releaseDate(movie),
                uniqueId(movie, "tmdb"),
                firstNonBlank(uniqueId(movie, "imdb"), text(movie, "imdbid")));
    }

    private Integer year(Element movie) {
        Integer explicit = intOf(text(movie, "year"));
        if (explicit != null) {
            return explicit;
        }
        LocalDate released = releaseDate(movie);
        return released == null ? null : released.getYear();
    }

    /** {@code <runtime>} is minutes (sometimes "148 min"); {@code <durationinseconds>} is seconds. */
    private Integer runtimeMinutes(Element movie) {
        Integer minutes = leadingInt(text(movie, "runtime"));
        if (minutes != null && minutes > 0) {
            return minutes;
        }
        Integer seconds = leadingInt(text(movie, "durationinseconds"));
        if (seconds != null && seconds > 0) {
            return Math.max(1, seconds / 60);
        }
        return null;
    }

    /**
     * Handles both the legacy scalar {@code <rating>8.8</rating>} and the current
     * {@code <ratings><rating default="true"><value>8.8</value></rating></ratings>}.
     */
    private Double rating(Element movie) {
        Element ratings = firstElement(movie, "ratings");
        if (ratings != null) {
            NodeList list = ratings.getElementsByTagName("rating");
            Element fallback = null;
            for (int i = 0; i < list.getLength(); i++) {
                if (!(list.item(i) instanceof Element candidate)) {
                    continue;
                }
                if (fallback == null) {
                    fallback = candidate;
                }
                if ("true".equalsIgnoreCase(candidate.getAttribute("default"))) {
                    Double value = doubleOf(text(candidate, "value"));
                    if (value != null) {
                        return normaliseRating(value, candidate.getAttribute("max"));
                    }
                }
            }
            if (fallback != null) {
                Double value = doubleOf(text(fallback, "value"));
                if (value != null) {
                    return normaliseRating(value, fallback.getAttribute("max"));
                }
            }
        }
        Double scalar = doubleOf(text(movie, "rating"));
        return scalar == null ? null : normaliseRating(scalar, null);
    }

    /** Rescales onto 0-10 so a source using a 0-100 scale does not look like a perfect score. */
    private static Double normaliseRating(double value, String maxAttribute) {
        Integer max = intOf(maxAttribute);
        double scaled = (max != null && max > 0 && max != 10) ? value * 10.0 / max : value;
        if (scaled < 0) {
            return null;
        }
        return Math.round(Math.min(scaled, 10.0) * 10.0) / 10.0;
    }

    private Set<String> genres(Element movie) {
        Set<String> out = new LinkedHashSet<>();
        for (String genre : texts(movie, "genre")) {
            // A single element holding "Action / Thriller" is common; split it.
            out.addAll(FilenameParser.splitList(genre));
        }
        return out;
    }

    private List<String> actors(Element movie) {
        List<String> out = new ArrayList<>();
        NodeList list = movie.getElementsByTagName("actor");
        for (int i = 0; i < list.getLength() && out.size() < 30; i++) {
            if (list.item(i) instanceof Element actor) {
                String name = text(actor, "name");
                if (name != null) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    private LocalDate releaseDate(Element movie) {
        String raw = firstNonBlank(
                text(movie, "premiered"), text(movie, "releasedate"), text(movie, "aired"));
        if (raw == null || raw.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private String uniqueId(Element movie, String type) {
        NodeList list = movie.getElementsByTagName("uniqueid");
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element element
                    && type.equalsIgnoreCase(element.getAttribute("type"))) {
                String value = element.getTextContent();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    // --- small DOM helpers ---

    private static Element firstElement(Element parent, String name) {
        NodeList list = parent.getElementsByTagName(name);
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    /** Text of the first {@code name} descendant that is not blank, trimmed. */
    private static String text(Element parent, String name) {
        NodeList list = parent.getElementsByTagName(name);
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            String value = node.getTextContent();
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static List<String> texts(Element parent, String name) {
        List<String> out = new ArrayList<>();
        NodeList list = parent.getElementsByTagName(name);
        for (int i = 0; i < list.getLength(); i++) {
            String value = list.item(i).getTextContent();
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Integer intOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Parses the leading digits, so "148 min" yields 148. */
    private static Integer leadingInt(String raw) {
        if (raw == null) {
            return null;
        }
        int end = 0;
        String trimmed = raw.trim();
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? null : intOf(trimmed.substring(0, end));
    }

    private static Double doubleOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

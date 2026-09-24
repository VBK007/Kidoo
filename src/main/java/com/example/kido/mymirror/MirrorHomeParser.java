package com.example.kido.mymirror;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Pulls the titles off the mirror's home page: an id to ask playlist.php about, a name and a
 * poster. Two ways in, because the page is not ours and its markup drifts: a tile element
 * carrying {@code data-post} / {@code data-id}, or failing that a poster image whose file is
 * named after the title's numeric id.
 */
public final class MirrorHomeParser {

    public record Title(String id, String title, String image) {}

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    // .../poster/v/81234567.jpg and the like: the last path segment, digits only.
    private static final Pattern POSTER_ID = Pattern.compile("/(\\d{3,20})\\.(?:jpe?g|png|webp)(?:\\?.*)?$");

    private MirrorHomeParser() {
    }

    /** Titles in page order, each id once. {@code baseUrl} resolves relative poster paths. */
    public static List<Title> parse(String html, String baseUrl) {
        Document doc = Jsoup.parse(html == null ? "" : html, baseUrl == null ? "" : baseUrl);
        Map<String, Title> byId = new LinkedHashMap<>();

        for (Element tile : doc.select("[data-post], [data-id]")) {
            String id = tile.hasAttr("data-post") ? tile.attr("data-post") : tile.attr("data-id");
            if (!ID.matcher(id).matches() || byId.containsKey(id)) continue;
            Element img = tile.selectFirst("img");
            byId.put(id, new Title(id, nameOf(tile, img), img == null ? null : imageOf(img)));
        }

        for (Element img : doc.select("img")) {
            String image = imageOf(img);
            if (image == null) continue;
            Matcher m = POSTER_ID.matcher(image);
            if (m.find() && !byId.containsKey(m.group(1))) {
                byId.put(m.group(1), new Title(m.group(1), nameOf(img, img), image));
            }
        }
        return new ArrayList<>(byId.values());
    }

    // Lazy-loaded pages keep the real poster in data-src and a placeholder in src.
    private static String imageOf(Element img) {
        for (String attr : new String[] {"data-src", "src"}) {
            if (img.hasAttr(attr) && !img.attr(attr).isBlank() && !img.attr(attr).startsWith("data:")) {
                String abs = img.absUrl(attr);
                return abs.isEmpty() ? img.attr(attr) : abs;
            }
        }
        return null;
    }

    private static String nameOf(Element tile, Element img) {
        for (String candidate : new String[] {
                tile.attr("data-title"), tile.attr("title"), img == null ? "" : img.attr("alt"), tile.text()}) {
            if (candidate != null && !candidate.isBlank()) return candidate.trim();
        }
        return null;
    }
}

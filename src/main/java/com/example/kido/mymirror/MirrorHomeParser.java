package com.example.kido.mymirror;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pulls the titles off the mirror's home catalogue, which arrives in one of two shapes depending on
 * the OTT (see {@link MirrorOtt}):
 *
 * <ul>
 *   <li>Netflix / default — {@code GET /mobile/home?app=1} answers the categorised home as
 *       server-rendered HTML: one {@code <h2 class="tray-title">} heading per rail (its text the
 *       category) followed by that rail's tiles, each a {@code <div data-post="<id>">}. The page
 *       names the category and the id, but not the title's name, and the posters are lazy-loaded
 *       placeholders — so the name is left null for the caller to look up and the image is rebuilt
 *       from the id.</li>
 *   <li>Prime Video — {@code GET /pv/homepage.php} answers JSON: a {@code slider} of featured ids
 *       and {@code post} rows of comma-joined ids, each row carrying its own {@code cate} label.</li>
 * </ul>
 *
 * <p>{@link #parseHome} sniffs which shape the body is (an HTML document vs a JSON object) and parses
 * accordingly, so the caller hands over the raw response and need not know the OTT's feed format.
 */
public final class MirrorHomeParser {

    /**
     * One parsed tile. {@code category} is the home's own grouping label for it — the Netflix rail
     * heading, or Prime's {@code cate} — or {@code null} where the tile sits outside any group
     * (Prime's featured slider, or a Netflix tile above the first heading). The home service turns
     * each distinct category into its own rail and folds the uncategorised ones into a single rail.
     */
    public record Title(String id, String title, String image, String category) {}

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private MirrorHomeParser() {
    }

    /**
     * The home catalogue as titles, each id once, for the {@code ott} catalogue. Sniffs the body:
     * an HTML document (Netflix's {@code /mobile/home?app=1}) parses through {@link #parseHomeHtml},
     * a JSON object (Prime's {@code /pv/homepage.php}) through {@link #parsePrimeHome}. A {@code null}
     * or blank body, or one that carries no tiles, yields none. Malformed JSON throws, as before, for
     * the home service to treat as a transient feed failure.
     */
    public static List<Title> parseHome(String body, String ott) {
        if (body == null) return List.of();
        String lead = body.stripLeading();
        if (lead.isEmpty()) return List.of();
        // '<' opens the Netflix home HTML; anything else is the Prime homepage JSON object.
        if (lead.charAt(0) == '<') return parseHomeHtml(body, ott);
        return parsePrimeHome(MAPPER.readTree(body));
    }

    /**
     * Netflix's {@code /mobile/home?app=1}: headings and tiles walked in document order so each tile
     * takes the category of the nearest {@code <h2 class="tray-title">} above it. The page names no
     * title and its posters are lazy placeholders, so the name is left null for the caller to look up
     * and the image is rebuilt from the id (see {@link #poster}). A tile whose {@code data-post} is
     * not a catalogue id, and a repeated id, are dropped.
     */
    static List<Title> parseHomeHtml(String html, String ott) {
        List<Title> titles = new ArrayList<>();
        Document doc = Jsoup.parse(html == null ? "" : html);
        Set<String> seen = new LinkedHashSet<>();
        String category = null;
        for (Element el : doc.select("h2.tray-title, [data-post]")) {
            if (el.is("h2.tray-title")) {
                String text = el.text().trim();
                category = text.isEmpty() ? null : text;
                continue;
            }
            String id = el.attr("data-post").trim();
            if (!ID.matcher(id).matches() || !seen.add(id)) continue;
            titles.add(new Title(id, null, poster(ott, id), category));
        }
        return titles;
    }

    /**
     * Prime Video's {@code /pv/homepage.php}: a {@code slider} of featured ids and {@code post} rows
     * of comma-joined ids ({@code {"ids":"a,b,c","cate":"Top 10 Movies in Prime"}}), first rows the
     * top tens. Each post row carries its own {@code cate}, kept on every title it holds so the home
     * service can build a rail per category; the slider's featured tiles carry none. It names nothing,
     * so each title's name is left null for the caller to look up; the poster lives at a fixed CDN
     * path by id (see {@link #poster}).
     */
    static List<Title> parsePrimeHome(JsonNode feed) {
        List<Title> titles = new ArrayList<>();
        if (feed == null) return titles;
        Set<String> seen = new LinkedHashSet<>();
        // The featured slider has no category of its own; its tiles fall into the single
        // "From the mirror" rail, ahead of the category rails the post rows build below.
        for (JsonNode entry : feed.path("slider")) {
            addPrime(entry.path("id").asString(""), null, titles, seen);
        }
        for (JsonNode row : feed.path("post")) {
            String category = row.path("cate").asString("").trim();
            for (String id : row.path("ids").asString("").split(",")) {
                addPrime(id, category.isEmpty() ? null : category, titles, seen);
            }
        }
        return titles;
    }

    private static void addPrime(String raw, String category, List<Title> titles, Set<String> seen) {
        String id = raw.trim();
        if (!ID.matcher(id).matches() || !seen.add(id)) return;
        titles.add(new Title(id, null, poster("pv", id), category));
    }

    /**
     * The poster for a title, built from its native id at the OTT's fixed per-catalogue CDN path.
     * The mirror's home feeds carry ids only — Netflix's {@code /mobile/home?app=1} names the
     * category but not the title and serves lazy placeholder artwork, Prime's {@code homepage.php}
     * ids alone — so the image is reconstructed, not read. Each OTT serves one uniform shape,
     * confirmed against the live grids on device (2026-11-21): Netflix at {@code nf/v/200/<id>.jpg}
     * keyed by the numeric id, Prime Video at {@code pv/341/<asin>.jpg}, Hotstar (the {@code dp}
     * catalogue) at {@code hs/v/166/<id>.jpg} keyed by its numeric id. An id that does not fit the
     * catalogue's namespace, or an unknown catalogue, yields {@code null} rather than a guessed URL —
     * the device then shows no artwork instead of a broken image, and a stale segment likewise
     * degrades to no artwork.
     *
     * <p>A {@code null}/blank {@code ott} is the default home load: the caller named no catalogue, so
     * upstream answers the Netflix home (all-digit ids). It is treated as Netflix here so that default
     * load still gets artwork — without this the home rail, which carries no {@code X-Mirror-Ott},
     * built every tile with a null poster and the app fell back to a monogram.
     */
    public static String poster(String ott, String id) {
        if (id == null || id.isBlank()) return null;
        if ("pv".equals(ott)) return "https://imgcdn.kim/pv/341/" + id + ".jpg";
        if ("dp".equals(ott) && id.chars().allMatch(Character::isDigit)) {
            return "https://imgcdn.kim/hs/v/166/" + id + ".jpg";
        }
        boolean netflix = "nf".equals(ott) || ott == null || ott.isBlank();
        if (netflix && id.chars().allMatch(Character::isDigit)) {
            return "https://imgcdn.kim/nf/v/200/" + id + ".jpg";
        }
        return null;
    }
}

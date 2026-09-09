package com.example.kido.media.metadata;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Last-resort metadata: pull a title and year out of a release-style filename.
 *
 * <p>Used when a file has no {@code .nfo} beside it. Handles the two dominant
 * conventions — {@code Inception.2010.1080p.BluRay.x264.mkv} and
 * {@code Inception (2010) [1080p].mkv} — by finding the year and treating
 * everything before it as the title, since release tags always follow the year.
 */
@Component
public class FilenameParser {

    /** A plausible film year in parentheses, brackets or bare, as its own token. */
    private static final Pattern YEAR =
            Pattern.compile("[(\\[. _-](19[0-9]{2}|20[0-3][0-9])[)\\]. _-]?");

    /** Tags that mark the start of release junk when no year is present. */
    private static final Pattern TAG_START = Pattern.compile(
            "(?i)[.\\s_-](480p|576p|720p|1080p|1440p|2160p|4k|8k|bluray|blu-ray|bdrip|brrip|"
            + "webrip|web-dl|webdl|web|hdrip|dvdrip|dvdscr|hdtv|remux|x264|x265|h264|h265|hevc|"
            + "avc|xvid|divx|aac|ac3|eac3|dts|dd5|truehd|atmos|10bit|hdr|dv|sdr|multi|dual)");

    private static final Pattern RESOLUTION = Pattern.compile("(?i)(480p|576p|720p|1080p|1440p|2160p|4k)");
    private static final Pattern SOURCE = Pattern.compile(
            "(?i)(bluray|blu-ray|bdrip|brrip|remux|web-dl|webdl|webrip|web|hdrip|dvdrip|hdtv)");

    private static final Set<String> LEADING_ARTICLES = Set.of("the", "a", "an");

    /**
     * A camera/phone capture stamp: {@code 20240102_181500}, {@code 2024-01-02 18.15.00}
     * or just {@code 2024-01-02}. Time separators are optional because every vendor
     * picks a different one, and trailing milliseconds are ignored.
     */
    private static final Pattern CAPTURE_STAMP = Pattern.compile(
            "(?<year>(?:19|20)\\d{2})[-_.]?(?<month>\\d{2})[-_.]?(?<day>\\d{2})"
                    + "(?:[-_.T ]?(?<hour>\\d{2})[-_.:]?(?<minute>\\d{2})[-_.:]?(?<second>\\d{2})?)?");

    /**
     * @param baseName filename with the extension already stripped
     */
    public Parsed parse(String baseName) {
        String cleaned = baseName.replace('_', ' ').trim();

        Integer year = null;
        int titleEnd = cleaned.length();

        Matcher ym = YEAR.matcher(cleaned);
        int lastStart = -1;
        String lastYear = null;
        // Take the *last* year match: "2001 A Space Odyssey (1968)" must yield 1968.
        while (ym.find()) {
            lastStart = ym.start();
            lastYear = ym.group(1);
        }
        if (lastYear != null && lastStart > 0) {
            year = Integer.valueOf(lastYear);
            titleEnd = lastStart;
        } else {
            Matcher tm = TAG_START.matcher(cleaned);
            if (tm.find() && tm.start() > 0) {
                titleEnd = tm.start();
            }
        }

        String title = normaliseTitle(cleaned.substring(0, titleEnd));
        if (title.isBlank()) {
            title = cleaned.isBlank() ? baseName : cleaned;
        }

        return new Parsed(title, year, quality(cleaned));
    }

    /** Turns {@code "Inception.2010"}-style separators into spaces and tidies whitespace. */
    private static String normaliseTitle(String raw) {
        String spaced = raw.replaceAll("[._]+", " ")
                .replaceAll("[(\\[{]+\\s*$", "")
                .replaceAll("\\s*[-–]\\s*$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return spaced;
    }

    /** Joins the resolution and source tags, e.g. {@code "1080p BluRay"}. */
    private static String quality(String cleaned) {
        List<String> parts = new ArrayList<>();
        Matcher r = RESOLUTION.matcher(cleaned);
        if (r.find()) parts.add(r.group(1).toLowerCase(Locale.ROOT).replace("4k", "2160p"));
        Matcher s = SOURCE.matcher(cleaned);
        if (s.find()) {
            String src = s.group(1).toLowerCase(Locale.ROOT);
            parts.add(switch (src) {
                case "blu-ray" -> "BluRay";
                case "bluray" -> "BluRay";
                case "web-dl", "webdl" -> "WEB-DL";
                case "webrip" -> "WEBRip";
                case "bdrip" -> "BDRip";
                case "brrip" -> "BRRip";
                case "hdrip" -> "HDRip";
                case "dvdrip" -> "DVDRip";
                case "remux" -> "Remux";
                case "hdtv" -> "HDTV";
                default -> src.toUpperCase(Locale.ROOT);
            });
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /**
     * Extracts a capture timestamp from a camera or phone filename.
     *
     * <p>Phones and cameras encode the moment in the name — {@code VID_20240102_181500},
     * {@code IMG-20231225-WA0003}, {@code PXL_20240102_181500123}, {@code 2024-01-02
     * 18.15.00} — and that is far more reliable than any filesystem timestamp, which a
     * copy between disks resets. Only the date part is required; a missing time becomes
     * midnight.
     *
     * <p>Interpreted in the system zone: a home camera writes local wall-clock time with
     * no zone information, so the server's own zone is the closest available guess.
     *
     * @return the capture instant, or empty if the name carries no plausible date
     */
    public Optional<Instant> captureInstant(String baseName) {
        Matcher matcher = CAPTURE_STAMP.matcher(baseName);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            int year = Integer.parseInt(matcher.group("year"));
            int month = Integer.parseInt(matcher.group("month"));
            int day = Integer.parseInt(matcher.group("day"));

            // Guard against matching an unrelated run of digits.
            if (year < 1970 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
                return Optional.empty();
            }

            int hour = optionalInt(matcher.group("hour"));
            int minute = optionalInt(matcher.group("minute"));
            int second = optionalInt(matcher.group("second"));
            if (hour > 23 || minute > 59 || second > 59) {
                hour = 0;
                minute = 0;
                second = 0;
            }

            return Optional.of(LocalDateTime.of(year, month, day, hour, minute, second)
                    .atZone(ZoneId.systemDefault())
                    .toInstant());
        } catch (NumberFormatException | DateTimeException ex) {
            // A date-shaped but invalid string, e.g. 20240230.
            return Optional.empty();
        }
    }

    private static int optionalInt(String group) {
        if (group == null || group.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(group);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Alphabetical sort key: lower-cased with a leading article moved off the front,
     * so "The Matrix" files under M.
     */
    public static String sortTitle(String title) {
        if (title == null || title.isBlank()) return "";
        String lower = title.toLowerCase(Locale.ROOT).trim();
        int space = lower.indexOf(' ');
        if (space > 0 && LEADING_ARTICLES.contains(lower.substring(0, space))) {
            return lower.substring(space + 1).trim();
        }
        return lower;
    }

    /** Genres/tags occasionally appear as a bracketed list; kept for future use. */
    public static Set<String> splitList(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String part : raw.split("[,/|]")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public record Parsed(String title, Integer year, String quality) {}
}

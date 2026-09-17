package com.example.kido.media.search;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuery.Range;
import com.example.kido.media.query.CatalogQuery.WatchedBy;

/**
 * Turns a typed sentence into a {@link CatalogQuery}.
 *
 * <p>No model, no network, no key. "Tamil films under 2 hours rated over 8" is a
 * grammar, and the entities in it — genres, names, languages — are a list this server
 * already has, so the usual hard part of natural-language search is not hard here. What
 * it cannot parse it says so about, rather than guessing.
 *
 * <p>Pure and static, like the two scorers: the parse is a judgement, and a judgement
 * should be testable without a database. The library's vocabulary arrives as a
 * {@link SearchVocabulary}.
 *
 * <p>The method is consume-and-narrow. Each rule matches a span, records what it
 * understood, and removes that span from the text; whatever survives every rule is a
 * title search. That ordering is why the specific rules run before the general ones —
 * "never watched" has to be taken before "watched", or the sentence is read backwards.
 *
 * <p>Everything it understood comes back as {@link Term}s so a client can render the
 * parse as removable chips. That display is what makes the parser's inevitable misses
 * survivable: a person sees what the server heard and corrects it by tapping, instead of
 * rephrasing at a black box.
 */
public final class SearchQueryParser {

    /** Words that carry no filter. Stripped so they do not become a title search. */
    private static final Set<String> NOISE = Set.of(
            "show", "me", "us", "find", "get", "give", "list", "search", "look", "for",
            "something", "anything", "some", "any", "a", "an", "the", "with", "in", "of",
            "that", "which", "is", "are", "i", "we", "want", "to", "watch", "please",
            "and", "or", "my", "our", "all", "movies", "films", "film", "movie", "titles",
            "title", "stuff", "things", "got", "have", "has", "there", "on", "at", "by",
            "from", "released", "made", "about", "like", "it", "its", "was", "were",
            "do", "does", "can", "you", "not", "but", "so", "this", "these");

    /** Spelled-out numbers people type in "under two hours". */
    private static final Map<String, Integer> WORD_NUMBERS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6),
            Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
            Map.entry("half", 0));

    private static final String NUMBER = "(\\d+(?:\\.\\d+)?|one|two|three|four|five|six|"
            + "seven|eight|nine|ten|eleven|twelve)";
    private static final String UNDER = "(?:under|below|less than|shorter than|at most|"
            + "no (?:more|longer) than|within)";
    private static final String OVER = "(?:over|above|more than|longer than|at least|"
            + "greater than)";

    /** What "highly rated" means, when somebody does not give a number. */
    private static final double HIGH_RATING = 8.0;

    private SearchQueryParser() {}

    /**
     * One thing the parser understood, for the client's chip row.
     *
     * @param field   which filter it set, e.g. {@code language}
     * @param value   the value it set, as the query carries it
     * @param label   how to print it, e.g. {@code Tamil}
     * @param matched the words it came from, so a person can see what was consumed
     */
    public record Term(String field, String value, String label, String matched) {}

    /**
     * @param query     what to run; empty when nothing was understood
     * @param terms     what was understood, in the order the rules matched
     * @param unmatched the words no rule claimed and that did not become a title search —
     *                  empty when the sentence was fully understood, and the input a
     *                  model would be handed if one were available
     */
    public record Parsed(CatalogQuery query, List<Term> terms, String unmatched) {

        public boolean understoodNothing() {
            return terms.isEmpty();
        }
    }

    public static Parsed parse(String text, SearchVocabulary vocabulary) {
        if (text == null || text.isBlank()) {
            return new Parsed(CatalogQuery.builder().build().validated(), List.of(), "");
        }

        Scratch scratch = new Scratch(normalise(text));
        CatalogQuery.CatalogQueryBuilder query = CatalogQuery.builder();
        List<Term> terms = new ArrayList<>();

        // Order is the grammar. Specific phrases first, so "never watched" is taken
        // before "watched" and "science fiction" before "fiction".
        watchState(scratch, query, terms);
        // Sort before liked and before rating: "most liked" and "top rated" each contain
        // a word the later rules claim, and whichever runs first wins the span.
        sort(scratch, query, terms);
        liked(scratch, query, terms);
        runtime(scratch, query, terms);
        rating(scratch, query, terms);
        years(scratch, query, terms);
        quality(scratch, query, terms);
        vocabularyTerms(scratch, query, terms, vocabulary);
        types(scratch, query, terms);

        String leftover = leftover(scratch);
        String unmatched = "";
        if (!leftover.isEmpty()) {
            // Whatever survived every rule is most likely a title. It is a guess, but a
            // visible one: it comes back as a term like any other and can be dismissed.
            query.titleContains(leftover);
            terms.add(new Term("title", leftover, "Title: " + leftover, leftover));
        }

        return new Parsed(query.build().validated(), List.copyOf(terms), unmatched);
    }

    // --- rules ---

    private static void watchState(Scratch text, CatalogQuery.CatalogQueryBuilder query,
                                   List<Term> terms) {
        if (text.take("\\b(?:never watched|nobody(?: has)? (?:seen|watched)|"
                + "no ?one(?: has)? (?:seen|watched)|none of us (?:has|have) (?:seen|watched))\\b")
                != null) {
            query.watched(WatchedBy.NOBODY);
            terms.add(new Term("watched", "NOBODY", "Nobody has watched", text.lastMatch()));
            return;
        }
        if (text.take("\\b(?:(?:we|someone|anyone)(?: has| have)? (?:seen|watched)|"
                + "already (?:seen|watched) (?:here|by us))\\b") != null) {
            query.watched(WatchedBy.SOMEONE);
            terms.add(new Term("watched", "SOMEONE", "Someone has watched", text.lastMatch()));
            return;
        }
        if (text.take("\\b(?:unwatched|not (?:yet )?(?:seen|watched)|"
                + "(?:i )?(?:have ?not|haven'?t|hav'?nt) (?:seen|watched)|new to me)\\b")
                != null) {
            query.watched(WatchedBy.NOT_ME);
            terms.add(new Term("watched", "NOT_ME", "I have not watched", text.lastMatch()));
            return;
        }
        if (text.take("\\b(?:(?:i(?:'ve)?|i have) (?:already )?(?:seen|watched)|rewatch)\\b")
                != null) {
            query.watched(WatchedBy.ME);
            terms.add(new Term("watched", "ME", "I have watched", text.lastMatch()));
        }
    }

    private static void liked(Scratch text, CatalogQuery.CatalogQueryBuilder query,
                              List<Term> terms) {
        if (text.take("\\b(?:(?:i|we) liked?|liked|favou?rites?)\\b") != null) {
            query.liked(true);
            terms.add(new Term("liked", "true", "Liked", text.lastMatch()));
        }
    }

    /**
     * "under 2 hours", "over 90 minutes", "2 hours or less".
     *
     * <p>Hours are converted to minutes here so the query carries one unit — the column
     * is minutes and a query that sometimes meant hours would be a trap for everything
     * downstream.
     */
    private static void runtime(Scratch text, CatalogQuery.CatalogQueryBuilder query,
                                List<Term> terms) {
        String unit = "(hours?|hrs?|h|minutes?|mins?|m)";

        Matcher under = text.match(UNDER + "\\s+" + NUMBER + "\\s*" + unit + "\\b");
        if (under != null) {
            int minutes = toMinutes(under.group(1), under.group(2));
            text.consume(under);
            query.runtimeMinutes(Range.atMost(minutes));
            terms.add(new Term("runtime", "<=" + minutes, "Under " + describe(minutes),
                    text.lastMatch()));
            return;
        }
        Matcher orLess = text.match(NUMBER + "\\s*" + unit + "\\s+or\\s+(?:less|under|fewer)");
        if (orLess != null) {
            int minutes = toMinutes(orLess.group(1), orLess.group(2));
            text.consume(orLess);
            query.runtimeMinutes(Range.atMost(minutes));
            terms.add(new Term("runtime", "<=" + minutes, "Under " + describe(minutes),
                    text.lastMatch()));
            return;
        }
        Matcher over = text.match(OVER + "\\s+" + NUMBER + "\\s*" + unit + "\\b");
        if (over != null) {
            int minutes = toMinutes(over.group(1), over.group(2));
            text.consume(over);
            query.runtimeMinutes(Range.atLeast(minutes));
            terms.add(new Term("runtime", ">=" + minutes, "Over " + describe(minutes),
                    text.lastMatch()));
        }
    }

    private static void rating(Scratch text, CatalogQuery.CatalogQueryBuilder query,
                               List<Term> terms) {
        String named = "(?:rating|rated|imdb|score|stars?)";

        Matcher above = text.match(
                named + "\\s*(?:of\\s*)?(?:" + OVER + "|>=?|better than)\\s*(\\d+(?:\\.\\d+)?)");
        if (above == null) {
            above = text.match("(\\d+(?:\\.\\d+)?)\\s*\\+\\s*" + named);
        }
        if (above == null) {
            // "over 8 rating" — the number leads and the word follows.
            above = text.match(OVER + "\\s*(\\d+(?:\\.\\d+)?)\\s*" + named);
        }
        if (above != null) {
            double min = Double.parseDouble(above.group(1));
            text.consume(above);
            query.rating(Range.atLeast(min));
            terms.add(new Term("rating", ">=" + min, "Rated " + trim(min) + "+",
                    text.lastMatch()));
            return;
        }

        Matcher below = text.match(
                named + "\\s*(?:of\\s*)?(?:" + UNDER + "|<=?|worse than)\\s*(\\d+(?:\\.\\d+)?)");
        if (below != null) {
            double max = Double.parseDouble(below.group(1));
            text.consume(below);
            query.rating(Range.atMost(max));
            terms.add(new Term("rating", "<=" + max, "Rated under " + trim(max),
                    text.lastMatch()));
            return;
        }

        if (text.take("\\b(?:highly|well|best|top|greatly) (?:rated|reviewed)\\b") != null
                || text.take("\\b(?:acclaimed|excellent)\\b") != null) {
            query.rating(Range.atLeast(HIGH_RATING));
            terms.add(new Term("rating", ">=" + HIGH_RATING,
                    "Rated " + trim(HIGH_RATING) + "+", text.lastMatch()));
        }
    }

    /**
     * Decades, exact years and open bounds.
     *
     * <p>A bare two-digit decade is read as the twentieth century up to the nineties and
     * the twenty-first after it: "the 90s" is 1990 and "the 20s" is 2020, because nobody
     * browsing a home library means the silent era.
     */
    private static void years(Scratch text, CatalogQuery.CatalogQueryBuilder query,
                              List<Term> terms) {
        Matcher fourDigitDecade = text.match("\\b(1\\d{3}|20\\d{2})s\\b");
        if (fourDigitDecade != null) {
            int decade = (Integer.parseInt(fourDigitDecade.group(1)) / 10) * 10;
            text.consume(fourDigitDecade);
            query.year(Range.between(decade, decade + 9));
            terms.add(new Term("year", decade + "-" + (decade + 9), "The " + decade + "s",
                    text.lastMatch()));
            return;
        }
        Matcher twoDigitDecade = text.match("\\b(\\d{2})s\\b");
        if (twoDigitDecade != null) {
            int shorthand = Integer.parseInt(twoDigitDecade.group(1));
            int decade = shorthand >= 30 ? 1900 + shorthand : 2000 + shorthand;
            text.consume(twoDigitDecade);
            query.year(Range.between(decade, decade + 9));
            terms.add(new Term("year", decade + "-" + (decade + 9), "The " + decade + "s",
                    text.lastMatch()));
            return;
        }
        Matcher before = text.match("\\b(?:before|older than|earlier than)\\s+(\\d{4})\\b");
        if (before != null) {
            int year = Integer.parseInt(before.group(1));
            text.consume(before);
            query.year(Range.atMost(year - 1));
            terms.add(new Term("year", "<=" + (year - 1), "Before " + year, text.lastMatch()));
            return;
        }
        Matcher after = text.match("\\b(?:after|since|newer than)\\s+(\\d{4})\\b");
        if (after != null) {
            int year = Integer.parseInt(after.group(1));
            text.consume(after);
            query.year(Range.atLeast(year + 1));
            terms.add(new Term("year", ">=" + (year + 1), "After " + year, text.lastMatch()));
            return;
        }
        if (text.take("\\b(?:this year|from this year)\\b") != null) {
            int year = LocalDate.now(ZoneOffset.UTC).getYear();
            query.year(Range.atLeast(year));
            terms.add(new Term("year", ">=" + year, "This year", text.lastMatch()));
            return;
        }
        Matcher exact = text.match("\\b(?:from|in|released)?\\s*\\b(19\\d{2}|20\\d{2})\\b");
        if (exact != null) {
            int year = Integer.parseInt(exact.group(1));
            text.consume(exact);
            query.year(Range.between(year, year));
            terms.add(new Term("year", String.valueOf(year), String.valueOf(year),
                    text.lastMatch()));
        }
    }

    private static void quality(Scratch text, CatalogQuery.CatalogQueryBuilder query,
                                List<Term> terms) {
        if (text.take("\\b(?:4k|uhd|2160p?|ultra ?hd)\\b") != null) {
            query.minHeight(2160);
            terms.add(new Term("minHeight", "2160", "4K", text.lastMatch()));
            return;
        }
        if (text.take("\\b(?:1080p?|full ?hd|fhd)\\b") != null) {
            query.minHeight(1080);
            terms.add(new Term("minHeight", "1080", "1080p or better", text.lastMatch()));
            return;
        }
        if (text.take("\\b(?:720p?|hd)\\b") != null) {
            query.minHeight(720);
            terms.add(new Term("minHeight", "720", "HD or better", text.lastMatch()));
        }
    }

    private static void sort(Scratch text, CatalogQuery.CatalogQueryBuilder query,
                             List<Term> terms) {
        if (text.take("\\b(?:newest|latest|recently added|most recent|just added)\\b") != null) {
            query.sort("added");
            terms.add(new Term("sort", "added", "Newest first", text.lastMatch()));
            return;
        }
        // Longest first: "best rated" must be taken whole, or "rated" survives into the
        // leftover and becomes a title search for the word "rated".
        if (text.take("\\b(?:highest rated|top rated|best rated|best)\\b") != null) {
            query.sort("rating");
            terms.add(new Term("sort", "rating", "Best first", text.lastMatch()));
            return;
        }
        if (text.take("\\b(?:most liked|most popular)\\b") != null) {
            query.sort("likes");
            terms.add(new Term("sort", "likes", "Most liked first", text.lastMatch()));
        }
    }

    /**
     * The entities: people, then languages, then genres.
     *
     * <p>People first because their names are the longest phrases and the most likely to
     * contain another term — a cast list can hold a name that is also a genre word, and
     * consuming the whole name first stops half of it leaking into a title search.
     */
    private static void vocabularyTerms(Scratch text, CatalogQuery.CatalogQueryBuilder query,
                                        List<Term> terms, SearchVocabulary vocabulary) {
        if (vocabulary == null) {
            return;
        }

        Set<String> people = new LinkedHashSet<>();
        for (String person : vocabulary.phrasesLongestFirst(vocabulary.people())) {
            if (text.takeLiteral(person) != null) {
                people.add(person);
                terms.add(new Term("person", person, titleCase(person), person));
            }
        }
        if (!people.isEmpty()) {
            query.people(people);
        }

        Set<String> languages = new LinkedHashSet<>();
        for (String spelling : vocabulary.phrasesLongestFirst(vocabulary.languages().keySet())) {
            if (text.takeLiteral(spelling) != null) {
                String code = vocabulary.languages().get(spelling);
                if (languages.add(code)) {
                    terms.add(new Term("language", code,
                            com.example.kido.media.metadata.Languages.displayName(code),
                            spelling));
                }
            }
        }
        if (!languages.isEmpty()) {
            query.languages(languages);
            // "a Tamil film", not "a film with a Tamil track somewhere" — which is what
            // somebody typing a language into a search box means.
            query.primaryLanguageOnly(true);
        }

        Set<String> genres = new LinkedHashSet<>();
        for (String genre : vocabulary.phrasesLongestFirst(vocabulary.genres())) {
            if (text.takeLiteral(genre) != null) {
                genres.add(genre);
                terms.add(new Term("genre", genre, titleCase(genre), genre));
            }
        }
        if (!genres.isEmpty()) {
            query.genres(genres);
        }
    }

    private static void types(Scratch text, CatalogQuery.CatalogQueryBuilder query,
                              List<Term> terms) {
        Set<MediaType> types = new LinkedHashSet<>();
        if (text.take("\\banime\\b") != null) {
            types.add(MediaType.ANIME);
            terms.add(new Term("type", "ANIME", "Anime", text.lastMatch()));
        }
        if (text.take("\\b(?:home ?videos?|our (?:own )?videos?|ours)\\b") != null) {
            types.add(MediaType.HOME_VIDEO);
            terms.add(new Term("type", "HOME_VIDEO", "Ours", text.lastMatch()));
        }
        if (text.take("\\b(?:photos?|pictures?)\\b") != null) {
            types.add(MediaType.PHOTO);
            terms.add(new Term("type", "PHOTO", "Photos", text.lastMatch()));
        }
        if (text.take("\\b(?:music|songs?|albums?)\\b") != null) {
            types.add(MediaType.MUSIC);
            terms.add(new Term("type", "MUSIC", "Music", text.lastMatch()));
        }
        if (!types.isEmpty()) {
            query.types(types);
        }
    }

    // --- text handling ---

    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("[^a-z0-9'.+<>= -]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Whatever no rule claimed, minus the words that carry no filter. */
    private static String leftover(Scratch scratch) {
        List<String> kept = new ArrayList<>();
        for (String word : scratch.remaining().split("\\s+")) {
            String cleaned = word.replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
            if (!cleaned.isEmpty() && !NOISE.contains(cleaned)) {
                kept.add(cleaned);
            }
        }
        return String.join(" ", kept);
    }

    private static int toMinutes(String number, String unit) {
        double value = WORD_NUMBERS.containsKey(number)
                ? WORD_NUMBERS.get(number)
                : Double.parseDouble(number);
        boolean hours = unit.startsWith("h");
        return (int) Math.round(hours ? value * 60 : value);
    }

    private static String describe(int minutes) {
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        return minutes + " minutes";
    }

    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    private static String titleCase(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean startOfWord = true;
        for (char c : value.toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = c == ' ' || c == '-';
        }
        return out.toString();
    }

    /**
     * The sentence, with the parts already understood cut out of it.
     *
     * <p>Mutable and deliberately so: consume-and-narrow is the whole method, and each
     * rule needs to see a text the earlier rules have already taken their words from.
     */
    private static final class Scratch {

        private String text;
        private String lastMatch = "";

        Scratch(String text) {
            this.text = text;
        }

        Matcher match(String regex) {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            return matcher.find() ? matcher : null;
        }

        void consume(Matcher matcher) {
            lastMatch = matcher.group().trim();
            text = (text.substring(0, matcher.start()) + " " + text.substring(matcher.end()))
                    .replaceAll("\\s+", " ").trim();
        }

        /** Matches and consumes in one step, for rules with nothing to capture. */
        String take(String regex) {
            Matcher matcher = match(regex);
            if (matcher == null) {
                return null;
            }
            consume(matcher);
            return lastMatch;
        }

        /** For vocabulary values, which are literal phrases rather than patterns. */
        String takeLiteral(String phrase) {
            return take("\\b" + Pattern.quote(phrase) + "\\b");
        }

        String lastMatch() {
            return lastMatch;
        }

        String remaining() {
            return text;
        }
    }
}

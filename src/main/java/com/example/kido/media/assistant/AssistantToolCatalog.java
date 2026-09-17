package com.example.kido.media.assistant;

import java.util.List;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;

/**
 * The tools the assistant is offered, and their schemas.
 *
 * <p>Separate from {@link AssistantTools}, which implements them, because this is the
 * part the model reads: the descriptions here are the only instructions it gets about
 * what each tool means. They are written for that audience — what the tool answers, and
 * the traps in its arguments — rather than as documentation for a maintainer.
 *
 * <p>There are four, and the surface is small on purpose. Every tool is a way to be
 * misunderstood, and a question these four cannot answer is a missing tool, which is a
 * small concrete piece of work rather than a prompt to be tuned.
 */
final class AssistantToolCatalog {

    static final String SEARCH_LIBRARY = "search_library";
    static final String MOST_PLAYED = "most_played";
    static final String TASTE_PROFILE = "taste_profile";
    static final String LIST_COLLECTIONS = "list_collections";

    private AssistantToolCatalog() {}

    static List<ToolUnion> tools() {
        return List.of(searchLibrary(), mostPlayed(), tasteProfile(), listCollections())
                .stream().map(ToolUnion::ofTool).toList();
    }

    private static Tool searchLibrary() {
        return Tool.builder()
                .name(SEARCH_LIBRARY)
                .description("""
                        Search this household's own media library. Every argument is \
                        optional; omit anything the question does not mention rather than \
                        guessing, because a narrower search than was asked for is worse \
                        than a broad one.

                        'watched' is the one to get right: NOT_ME means the person asking \
                        has not finished it, NOBODY means nobody in the household has. \
                        "I haven't seen" is NOT_ME; "nobody has seen" and "never watched" \
                        are NOBODY.

                        Runtimes are in minutes. Ratings are 0-10.""")
                .inputSchema(schema(Map.ofEntries(
                        Map.entry("types", array("string",
                                "FILM, ANIME, HOME_VIDEO, MUSIC or PHOTO")),
                        Map.entry("title_contains", property("string",
                                "Words from the title, when a specific film is named")),
                        Map.entry("genres", array("string", "Genre names")),
                        Map.entry("genre_match", property("string",
                                "ANY (default) or ALL")),
                        Map.entry("people", array("string", "Cast or crew names")),
                        Map.entry("languages", array("string", "ISO 639-1 codes, e.g. ta")),
                        Map.entry("min_year", property("integer", "Earliest release year")),
                        Map.entry("max_year", property("integer", "Latest release year")),
                        Map.entry("min_runtime_minutes", property("integer",
                                "Shortest runtime in minutes")),
                        Map.entry("max_runtime_minutes", property("integer",
                                "Longest runtime in minutes; 'under 2 hours' is 120")),
                        Map.entry("min_rating", property("number", "Lowest rating, 0-10")),
                        Map.entry("max_rating", property("number", "Highest rating, 0-10")),
                        Map.entry("min_height", property("integer",
                                "2160 for 4K, 1080, or 720")),
                        Map.entry("watched", property("string",
                                "ME, NOT_ME, SOMEONE or NOBODY")),
                        Map.entry("liked", property("boolean",
                                "True for titles the person asking has liked")),
                        Map.entry("sort", property("string",
                                "title, added, year, rating or likes")),
                        Map.entry("limit", property("integer",
                                "How many to return, at most 25")))))
                .build();
    }

    private static Tool mostPlayed() {
        return Tool.builder()
                .name(MOST_PLAYED)
                .description("""
                        Titles the household has played most, with how many times each \
                        was played. Use this for questions about how often something has \
                        been watched — search_library cannot sort or filter on play count.

                        Note this counts times started by anyone in the household, not \
                        time spent watching.""")
                .inputSchema(schema(Map.of(
                        "min_plays", property("integer",
                                "Only titles played at least this many times"),
                        "limit", property("integer", "How many to return, at most 25"))))
                .build();
    }

    private static Tool tasteProfile() {
        return Tool.builder()
                .name(TASTE_PROFILE)
                .description("""
                        What this server has worked out about the taste of the person \
                        asking, from what they have finished, spent time on, liked and \
                        walked away from — with the titles it read each preference from.

                        Use it before recommending something, so a suggestion can say \
                        what it is based on. It describes the person asking and nobody \
                        else; there is no way to ask about another profile.""")
                .inputSchema(schema(Map.of()))
                .build();
    }

    private static Tool listCollections() {
        return Tool.builder()
                .name(LIST_COLLECTIONS)
                .description("""
                        The named collections this household has, with how many titles \
                        each holds. Prefer pointing at an existing collection over \
                        describing a search that would rebuild it.""")
                .inputSchema(schema(Map.of()))
                .build();
    }

    // --- schema helpers ---

    /**
     * Every argument is optional, so nothing is marked required.
     *
     * <p>That is a real decision rather than laziness: a required argument is one the
     * model must invent a value for when the question does not supply one, and an
     * invented filter narrows a search in a way nobody asked for and nobody can see.
     */
    private static Tool.InputSchema schema(Map<String, Map<String, Object>> properties) {
        Tool.InputSchema.Properties.Builder built = Tool.InputSchema.Properties.builder();
        properties.forEach((name, definition) ->
                built.putAdditionalProperty(name, JsonValue.from(definition)));
        return Tool.InputSchema.builder()
                .properties(built.build())
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
    }

    private static Map<String, Object> property(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> array(String itemType, String description) {
        return Map.of("type", "array", "description", description,
                "items", Map.of("type", itemType));
    }
}

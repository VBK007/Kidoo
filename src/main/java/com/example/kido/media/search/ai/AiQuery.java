package com.example.kido.media.search.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The shape the model is asked to fill in.
 *
 * <p>Deliberately not {@link com.example.kido.media.query.CatalogQuery} itself. That
 * record is the internal contract — it has a builder, nested range records, enums and a
 * validation step — and pointing a generated schema at it would couple the model's
 * output format to an object that changes for reasons having nothing to do with the
 * model. This is flat, plain and separately versionable: strings, numbers and lists,
 * every field optional, nothing nested.
 *
 * <p>Flat also makes it easier to get right. A schema with no nested objects is one the
 * model has fewer ways to fill in wrongly, and every field here maps to exactly one
 * filter on the far side.
 *
 * <p>The descriptions are the prompt. They travel with the schema, so the rules about
 * what a field means sit next to the field rather than in a paragraph the model has to
 * apply from memory.
 */
public record AiQuery(

        @JsonProperty("types")
        @JsonPropertyDescription("Media types, any of: FILM, ANIME, HOME_VIDEO, MUSIC, "
                + "PHOTO. Omit unless the person named one.")
        List<String> types,

        @JsonProperty("title_contains")
        @JsonPropertyDescription("Words to match against the title, when the person "
                + "seems to be naming a specific film rather than describing one.")
        String titleContains,

        @JsonProperty("genres")
        @JsonPropertyDescription("Genres, and ONLY ones from the supplied list of genres "
                + "this library holds. Never invent one.")
        List<String> genres,

        @JsonProperty("genre_match")
        @JsonPropertyDescription("ANY if one genre is enough, ALL if the person wants "
                + "titles carrying every genre listed. Default ANY.")
        String genreMatch,

        @JsonProperty("people")
        @JsonPropertyDescription("Cast or crew, and ONLY names from the supplied list. "
                + "Never invent one.")
        List<String> people,

        @JsonProperty("languages")
        @JsonPropertyDescription("ISO 639-1 codes, and ONLY ones from the supplied list "
                + "of languages this library holds.")
        List<String> languages,

        @JsonProperty("min_year")
        @JsonPropertyDescription("Earliest release year, inclusive.")
        Integer minYear,

        @JsonProperty("max_year")
        @JsonPropertyDescription("Latest release year, inclusive.")
        Integer maxYear,

        @JsonProperty("min_runtime_minutes")
        @JsonPropertyDescription("Shortest runtime in MINUTES, inclusive. Convert hours.")
        Integer minRuntimeMinutes,

        @JsonProperty("max_runtime_minutes")
        @JsonPropertyDescription("Longest runtime in MINUTES, inclusive. Convert hours: "
                + "'under 2 hours' is 120.")
        Integer maxRuntimeMinutes,

        @JsonProperty("min_rating")
        @JsonPropertyDescription("Lowest rating on a 0-10 scale, inclusive.")
        Double minRating,

        @JsonProperty("max_rating")
        @JsonPropertyDescription("Highest rating on a 0-10 scale, inclusive.")
        Double maxRating,

        @JsonProperty("min_height")
        @JsonPropertyDescription("Minimum vertical resolution: 2160 for 4K, 1080, 720.")
        Integer minHeight,

        @JsonProperty("watched")
        @JsonPropertyDescription("Whose watch state to filter on: ANYONE (no filter), "
                + "ME (I finished it), NOT_ME (I have not), SOMEONE (anyone here has), "
                + "NOBODY (nobody here has). 'unwatched' means NOT_ME; 'never watched' "
                + "and 'nobody has seen' mean NOBODY.")
        String watched,

        @JsonProperty("liked")
        @JsonPropertyDescription("True for titles the person liked.")
        Boolean liked,

        @JsonProperty("sort")
        @JsonPropertyDescription("One of: title, added, captured, year, rating, likes. "
                + "'newest' is added, 'best' is rating.")
        String sort,

        @JsonProperty("understood")
        @JsonPropertyDescription("False if the sentence is not a request for films at "
                + "all, or asks for something none of these fields can express. Set "
                + "every other field to null when this is false.")
        Boolean understood) {}

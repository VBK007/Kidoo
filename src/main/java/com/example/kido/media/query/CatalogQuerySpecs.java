package com.example.kido.media.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.data.jpa.domain.Specification;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.engagement.MediaItemLike;
import com.example.kido.media.playback.PlaybackProgress;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Turns a {@link CatalogQuery} into a database query.
 *
 * <p>Criteria rather than JPQL for the reason the catalog already had: the filters are
 * optional and combinable, and a JPQL guard like {@code (:genre is null or g = :genre)}
 * makes PostgreSQL unable to infer the bind parameter's type when the value is null. An
 * absent filter here simply contributes no predicate.
 *
 * <p>Two deliberate choices, both about correctness under paging:
 *
 * <p><b>Multi-valued facets are matched with {@code exists}, not a join.</b> Joining
 * onto {@code genres} multiplies a row by its genre count, which the old code patched
 * with {@code distinct}. That patches the rows and not the count: the paging count query
 * Spring Data derives has to be told the same thing, and any place it is not, the totals
 * are wrong. A subquery multiplies nothing, so there is nothing to correct.
 *
 * <p><b>Watch state is a predicate, not a post-filter.</b> It reads from another table,
 * which is presumably why it used to be applied to the page after it came back — but
 * filtering after paging means a short page and a total that counts rows the filter
 * would have removed. Asking the database costs one correlated subquery and makes
 * {@code totalItems} true.
 */
public final class CatalogQuerySpecs {

    private CatalogQuerySpecs() {}

    /**
     * @param query   already {@link CatalogQuery#validated()}
     * @param profileId whose watch state the per-profile filters mean; may be null when
     *                  the query asks about the household or about nobody
     */
    public static Specification<MediaItem> toSpecification(CatalogQuery query, String profileId) {
        return (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Missing and deliberately-hidden items are indexed but never browsable.
            predicates.add(cb.isFalse(root.get("missing")));
            predicates.add(cb.isFalse(root.get("hidden")));

            if (query.types() != null) {
                predicates.add(root.get("type").in(query.types()));
            }
            if (query.titleContains() != null) {
                predicates.add(titleMatches(root, cb, query.titleContains()));
            }
            addFacet(predicates, root, criteria, cb, "genres",
                    query.genres(), query.genreMatch());
            // Every named person must appear: "with both of them" is the only reading of
            // two cast names that anyone means.
            addFacet(predicates, root, criteria, cb, "people",
                    query.people(), CatalogQuery.MatchMode.ALL);
            addLanguages(predicates, root, criteria, cb, query);

            addRange(predicates, cb, root.get("year"), query.year());
            addRange(predicates, cb, root.get("runtimeMinutes"), query.runtimeMinutes());
            addRange(predicates, cb, root.get("rating"), query.rating());

            if (query.minHeight() != null && query.minHeight() > 0) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("mediaInfo").get("height"), query.minHeight()));
            }
            addWatched(predicates, root, criteria, cb, query.watched(), profileId);
            addLiked(predicates, root, criteria, cb, query.liked(), profileId);

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Predicate titleMatches(Root<MediaItem> root, CriteriaBuilder cb, String text) {
        // Already trimmed by validation; lowercased here because the column is not.
        String pattern = "%" + text.toLowerCase(Locale.ROOT) + "%";
        return cb.or(
                cb.like(cb.lower(root.get("title")), pattern),
                cb.like(cb.lower(root.get("sortTitle")), pattern),
                cb.like(cb.lower(root.get("fileName")), pattern),
                // Music is not looked for by its title. Nobody hunts down a song
                // by typing the song — they type who sang it, who scored it, or
                // the film it came from. Searching "Ilaiyaraaja" in a library
                // with a whole shelf of him returned nothing at all.
                //
                // castMembers is deliberately not here: it is mapped to a CLOB
                // and Hibernate will not put lower() round one, so including it
                // would mean a case-sensitive match that worked for some names
                // and silently not for others. Worse than not offering it.
                cb.like(cb.lower(root.get("artist")), pattern),
                cb.like(cb.lower(root.get("album")), pattern),
                cb.like(cb.lower(root.get("musicDirector")), pattern));
    }

    /**
     * An {@code exists} per required value, or one {@code exists} with an {@code in} for
     * "any of".
     *
     * <p>One subquery per value looks heavier than a single join and is not: the values
     * are a handful at most, each subquery is an index lookup on the collection table,
     * and neither form multiplies the outer row — which is what actually costs, because
     * the alternative is a {@code distinct} over the whole result set.
     */
    private static void addFacet(List<Predicate> predicates,
                                 Root<MediaItem> root,
                                 CriteriaQuery<?> criteria,
                                 CriteriaBuilder cb,
                                 String attribute,
                                 Set<String> values,
                                 CatalogQuery.MatchMode mode) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (mode == CatalogQuery.MatchMode.ALL) {
            for (String value : values) {
                predicates.add(existsInCollection(root, criteria, cb, attribute, List.of(value)));
            }
        } else {
            predicates.add(
                    existsInCollection(root, criteria, cb, attribute, List.copyOf(values)));
        }
    }

    /**
     * {@code exists (select v from item.<attribute> v where lower(v) in (...))}, as a
     * subquery correlated back to the row being tested.
     *
     * <p>Values arrive lowercased from {@link CatalogQuery#validated()}, so only the
     * column needs lowering here.
     */
    private static Predicate existsInCollection(Root<MediaItem> root,
                                                CriteriaQuery<?> criteria,
                                                CriteriaBuilder cb,
                                                String attribute,
                                                List<String> values) {
        Subquery<String> subquery = criteria.subquery(String.class);
        Root<MediaItem> correlated = subquery.correlate(root);
        Expression<String> element = correlated.join(attribute);
        subquery.select(element).where(cb.lower(element).in(values));
        return cb.exists(subquery);
    }

    private static void addLanguages(List<Predicate> predicates,
                                     Root<MediaItem> root,
                                     CriteriaQuery<?> criteria,
                                     CriteriaBuilder cb,
                                     CatalogQuery query) {
        if (query.languages() == null || query.languages().isEmpty()) {
            return;
        }
        if (query.primaryLanguageOnly()) {
            // The first audio track, which is what a player picks by default — "a Tamil
            // film" rather than "a film with a Tamil track somewhere".
            predicates.add(cb.lower(root.get("primaryLanguage")).in(query.languages()));
            return;
        }
        predicates.add(
                existsInCollection(root, criteria, cb, "languages", List.copyOf(query.languages())));
    }

    private static void addRange(List<Predicate> predicates,
                                 CriteriaBuilder cb,
                                 Expression<? extends Number> column,
                                 CatalogQuery.Range range) {
        if (range == null || range.isEmpty()) {
            return;
        }
        if (range.min() != null) {
            predicates.add(cb.ge(column, range.min()));
        }
        if (range.max() != null) {
            predicates.add(cb.le(column, range.max()));
        }
    }

    /**
     * Whether anyone, this profile, or nobody has finished the title.
     *
     * <p>{@code watched} lives on {@code PlaybackProgress}, one row per profile per
     * item, so every variant is the same correlated count with a different comparison —
     * and the household ones simply leave the profile out of it.
     */
    private static void addWatched(List<Predicate> predicates,
                                   Root<MediaItem> root,
                                   CriteriaQuery<?> criteria,
                                   CriteriaBuilder cb,
                                   CatalogQuery.WatchedBy watched,
                                   String profileId) {
        if (watched == null || watched == CatalogQuery.WatchedBy.ANYONE) {
            return;
        }
        boolean perProfile = watched == CatalogQuery.WatchedBy.ME
                || watched == CatalogQuery.WatchedBy.NOT_ME;
        if (perProfile && profileId == null) {
            // Nobody to ask about. Treated as no filter rather than as "nobody", because
            // an absent profile is a caller that forgot a header, not a claim about the
            // household.
            return;
        }

        Subquery<Long> finished = criteria.subquery(Long.class);
        Root<PlaybackProgress> progress = finished.from(PlaybackProgress.class);
        List<Predicate> on = new ArrayList<>();
        on.add(cb.equal(progress.get("mediaItemId"), root.get("id")));
        on.add(cb.isTrue(progress.get("watched")));
        if (perProfile) {
            on.add(cb.equal(progress.get("profileId"), profileId));
        }
        finished.select(cb.count(progress)).where(cb.and(on.toArray(new Predicate[0])));

        boolean wantFinished = watched == CatalogQuery.WatchedBy.ME
                || watched == CatalogQuery.WatchedBy.SOMEONE;
        predicates.add(wantFinished
                ? cb.greaterThan(finished, 0L)
                : cb.equal(finished, 0L));
    }

    /**
     * Liked by this profile, or not.
     *
     * <p>Against the like rows rather than the denormalised {@code likeCount}, which is
     * the household's total and cannot answer "did *I* like it".
     */
    private static void addLiked(List<Predicate> predicates,
                                 Root<MediaItem> root,
                                 CriteriaQuery<?> criteria,
                                 CriteriaBuilder cb,
                                 Boolean liked,
                                 String profileId) {
        if (liked == null || profileId == null) {
            return;
        }
        Subquery<Long> likes = criteria.subquery(Long.class);
        Root<MediaItemLike> like =
                likes.from(MediaItemLike.class);
        likes.select(cb.count(like)).where(cb.and(
                cb.equal(like.get("mediaItemId"), root.get("id")),
                cb.equal(like.get("profileId"), profileId)));

        predicates.add(liked ? cb.greaterThan(likes, 0L) : cb.equal(likes, 0L));
    }
}

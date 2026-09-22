package com.example.kido.admin.dto;

import java.util.List;

/**
 * Read models for the web admin dashboard: how many people use this server, from
 * which apps, and how much there is for them to use.
 *
 * <p>Shaped for the cards a dashboard actually draws rather than for the tables
 * underneath. Every number a tile shows is computed here, so the page never has to
 * sum a list to render a headline figure.
 *
 * <p>Counts are whole-server figures. Unlike the media admin panel's People tab,
 * which is about the profiles inside one household, these are about accounts —
 * the thing a signup creates and a subscription belongs to.
 */
public final class DashboardDtos {

    private DashboardDtos() {}

    // --- users ---

    /**
     * Accounts, and how many of them came back recently.
     *
     * <p>"Active" means signed in: it counts distinct accounts with a login event in
     * the window, which is what the apps report right after obtaining a token. An
     * account that is streaming from a token it already held is not counted again —
     * {@link EngagementDto#streamingNow()} is the figure for what is happening this
     * second.
     *
     * @param premium accounts whose subscription is paid <em>and</em> not yet expired
     * @param profiles viewing profiles across every account, always ≥ {@code total}
     *                 in a household that has set any up
     */
    public record UserStatsDto(
            long total,
            long parents,
            long children,
            long premium,
            long newToday,
            long newLast7Days,
            long newLast30Days,
            long profiles,
            long activeToday,
            long activeLast7Days,
            long activeLast30Days) {}

    // --- per application ---

    /**
     * One client application, as its sign-ins describe it.
     *
     * <p>The platform string is whatever the client declared when it logged the
     * sign-in ({@code android}, {@code ios}, {@code web}); {@code unknown} covers the
     * builds that declare nothing. It is free-form on purpose — a new app can appear
     * on this table without a server release teaching it the name first.
     *
     * <p>A person who uses both the phone and the browser counts once in each row, so
     * these do not sum to {@link UserStatsDto#total()}. That is the point of the
     * breakdown: it answers "how many people use the Android app", not "how do the
     * users divide up".
     *
     * @param lastSeenAt ISO-8601 instant of the most recent sign-in from this app
     */
    public record ApplicationStatsDto(
            String platform,
            long users,
            long activeLast7Days,
            long activeLast30Days,
            long loginsLast30Days,
            String lastSeenAt) {}

    // --- catalog ---

    /**
     * One library category.
     *
     * @param type  the {@code MediaType} name, stable for the client to switch on
     * @param label the chip label the apps already show for it
     */
    public record LibraryCategoryDto(String type, String label, long items, long bytes) {}

    /** A simple named tally — a poster category, a component kind. */
    public record CountDto(String key, long count) {}

    /**
     * Everything the household can browse, counted.
     *
     * <p>The named fields are the headline tiles a dashboard wants without filtering a
     * list; {@code byType} carries every category including the ones with no tile of
     * their own. Both exclude rows that are hidden or gone from disk, so the figures
     * match what the apps would actually list — {@code missingItems} reports the gone
     * ones separately rather than silently folding them in.
     */
    public record LibraryStatsDto(
            long movies,
            long anime,
            long series,
            long videoSongs,
            long homeVideos,
            long music,
            long photos,
            long adult,
            long totalItems,
            long totalBytes,
            long missingItems,
            long addedLast7Days,
            List<LibraryCategoryDto> byType) {}

    /** The ceremony poster catalog. */
    public record PosterStatsDto(
            long templates,
            long published,
            long unpublished,
            List<CountDto> byCategory,
            long components,
            List<CountDto> componentsByType) {}

    /** The versioned manifest the kids app syncs from. */
    public record ContentStatsDto(long items, long published) {}

    public record CatalogStatsDto(
            LibraryStatsDto library,
            PosterStatsDto posters,
            ContentStatsDto content) {}

    // --- engagement ---

    /**
     * What the server is doing right now, and how much it has been used lately.
     *
     * @param streamingNow distinct profiles with a live playback session, which is a
     *                     smaller number than {@code liveStreams} when someone has a
     *                     phone and a TV going at once
     */
    public record EngagementDto(
            int liveStreams,
            int streamingNow,
            int peakConcurrentStreams,
            double watchHoursLast7Days,
            double watchHoursThisMonth) {}

    // --- the whole page ---

    /**
     * Everything above in one call.
     *
     * <p>One request rather than four, because a dashboard's first paint wants all of
     * it and four round trips would draw the cards in four stages.
     *
     * @param generatedAt ISO-8601 instant these figures were computed, so a page left
     *                    open overnight can say how stale it is
     */
    public record DashboardDto(
            String generatedAt,
            UserStatsDto users,
            List<ApplicationStatsDto> applications,
            CatalogStatsDto catalog,
            EngagementDto engagement) {}
}

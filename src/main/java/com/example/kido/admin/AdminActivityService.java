package com.example.kido.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.admin.dto.ActivityDtos.LiveScreenDto;
import com.example.kido.admin.dto.ActivityDtos.RecentPlayDto;
import com.example.kido.admin.dto.ActivityDtos.RecentSignupDto;
import com.example.kido.analytics.LoginEvent;
import com.example.kido.analytics.LoginEventRepository;
import com.example.kido.analytics.ScreenView;
import com.example.kido.analytics.ScreenViewRepository;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.playback.PlaybackProgress;
import com.example.kido.media.playback.PlaybackProgressRepository;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;
import com.example.kido.user.AppUser;
import com.example.kido.user.UserRepository;

/**
 * Who's using the server right now, and who just joined — the two questions the
 * aggregate counts on the main dashboard can't answer on their own.
 */
@Service
public class AdminActivityService {

    /** A screen counts as "current" only this recently — past this, the account is
     * assumed to have left rather than to be reading one page for a very long time. */
    private static final Duration LIVE_WINDOW = Duration.ofMinutes(10);
    private static final int LIVE_LIMIT = 30;

    /** The recently-played feed is an audit view, not a dashboard tile: a generous page
     * so a reviewer can scroll a household's evening, but still bounded. */
    private static final int RECENT_PLAYED_MAX = 200;

    private final ScreenViewRepository screenViews;
    private final UserRepository users;
    private final LoginEventRepository logins;
    private final MediaItemRepository items;
    private final ProfileRepository profiles;
    private final PlaybackProgressRepository progress;

    public AdminActivityService(ScreenViewRepository screenViews, UserRepository users,
                                LoginEventRepository logins, MediaItemRepository items,
                                ProfileRepository profiles, PlaybackProgressRepository progress) {
        this.screenViews = screenViews;
        this.users = users;
        this.logins = logins;
        this.items = items;
        this.profiles = profiles;
        this.progress = progress;
    }

    /**
     * One row per account currently on a screen, newest first.
     *
     * <p>Merged in memory rather than with a correlated "latest row per group"
     * subquery: the page read here is bounded and small, and that shape of query is
     * exactly where H2 and PostgreSQL part ways — see {@link AdminDashboardService}.
     */
    @Transactional(readOnly = true)
    public List<LiveScreenDto> liveScreens() {
        Instant since = Instant.now().minus(LIVE_WINDOW);
        Map<String, ScreenView> latestPerUser = new LinkedHashMap<>();
        for (ScreenView view : screenViews.findTop200ByOrderByAtDesc()) {
            if (view.getAt().isBefore(since)) {
                break;
            }
            latestPerUser.putIfAbsent(view.getUserId(), view);
            if (latestPerUser.size() >= LIVE_LIMIT) {
                break;
            }
        }
        if (latestPerUser.isEmpty()) {
            return List.of();
        }

        Map<String, AppUser> accounts = indexById(users.findAllById(latestPerUser.keySet()), AppUser::getId);
        Set<String> itemIds = new HashSet<>();
        for (ScreenView view : latestPerUser.values()) {
            if (view.getItemId() != null) {
                itemIds.add(view.getItemId());
            }
        }
        Map<String, MediaItem> titles = indexById(items.findAllById(itemIds), MediaItem::getId);

        return latestPerUser.values().stream()
                .map(view -> {
                    MediaItem item = view.getItemId() == null ? null : titles.get(view.getItemId());
                    return new LiveScreenDto(
                            view.getUserId(),
                            label(accounts.get(view.getUserId())),
                            view.getProfileId(),
                            view.getScreen(),
                            view.getItemId(),
                            item == null ? null : item.getTitle(),
                            view.getPlatform(),
                            view.getAt());
                })
                .toList();
    }

    /**
     * The newest accounts, most recent first.
     *
     * <p>One login lookup per row rather than a join: this is ten accounts, not a
     * table scan, and {@link LoginEventRepository#findByUserIdOrderByAtDesc} already
     * exists for the account's own sign-in history.
     */
    @Transactional(readOnly = true)
    public List<RecentSignupDto> recentSignups() {
        return users.findTop10ByOrderByCreatedAtDesc().stream()
                .map(account -> {
                    List<LoginEvent> history = logins.findByUserIdOrderByAtDesc(account.getId());
                    LoginEvent lastLogin = history.isEmpty() ? null : history.get(0);
                    return new RecentSignupDto(
                            account.getId(),
                            account.getUsername(),
                            account.getEmail(),
                            account.getDisplayName(),
                            String.valueOf(account.getRole()),
                            account.getCreatedAt(),
                            lastLogin == null ? null : lastLogin.getPlatform(),
                            lastLogin == null ? null : lastLogin.getAt());
                })
                .toList();
    }

    /**
     * What each account has recently played, newest first — the audit feed.
     *
     * <p>The grain is the playback-progress row, which is an upsert on
     * {@code (profile, item)}, so a page of these is a page of <em>distinct</em> plays
     * rather than a replay of every heartbeat while a film was on. Each row carries a
     * profile id; the profiles resolve to their owning accounts, and the item ids to
     * titles, in two batched lookups rather than a join — the same in-memory join the
     * other admin read models use so the query stays portable between H2 and PostgreSQL.
     *
     * @param limit how many rows to return, clamped to {@link #RECENT_PLAYED_MAX}.
     */
    @Transactional(readOnly = true)
    public List<RecentPlayDto> recentlyPlayed(int limit) {
        int size = Math.max(1, Math.min(limit, RECENT_PLAYED_MAX));
        List<PlaybackProgress> rows = progress.findByOrderByUpdatedAtDesc(PageRequest.of(0, size));
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<String> profileIds = new HashSet<>();
        Set<String> itemIds = new HashSet<>();
        for (PlaybackProgress row : rows) {
            profileIds.add(row.getProfileId());
            itemIds.add(row.getMediaItemId());
        }

        Map<String, Profile> profilesById = indexById(profiles.findAllById(profileIds), Profile::getId);
        Set<String> ownerIds = new HashSet<>();
        for (Profile profile : profilesById.values()) {
            if (profile.getOwnerId() != null) {
                ownerIds.add(profile.getOwnerId());
            }
        }
        Map<String, AppUser> accounts = indexById(users.findAllById(ownerIds), AppUser::getId);
        Map<String, MediaItem> titles = indexById(items.findAllById(itemIds), MediaItem::getId);

        return rows.stream()
                .map(row -> {
                    Profile profile = profilesById.get(row.getProfileId());
                    String ownerId = profile == null ? null : profile.getOwnerId();
                    AppUser account = ownerId == null ? null : accounts.get(ownerId);
                    MediaItem item = titles.get(row.getMediaItemId());
                    return new RecentPlayDto(
                            ownerId,
                            label(account),
                            row.getProfileId(),
                            profile == null ? null : profile.getName(),
                            row.getMediaItemId(),
                            item == null ? null : item.getTitle(),
                            row.percentComplete(),
                            row.isWatched(),
                            row.getUpdatedAt());
                })
                .toList();
    }

    private static String label(AppUser account) {
        if (account == null) {
            return "unknown";
        }
        return account.getDisplayName() != null && !account.getDisplayName().isBlank()
                ? account.getDisplayName()
                : account.getUsername();
    }

    private static <T> Map<String, T> indexById(List<T> rows, Function<T, String> idOf) {
        Map<String, T> map = new HashMap<>();
        for (T row : rows) {
            map.put(idOf.apply(row), row);
        }
        return map;
    }
}

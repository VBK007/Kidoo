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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.admin.dto.ActivityDtos.LiveScreenDto;
import com.example.kido.admin.dto.ActivityDtos.RecentSignupDto;
import com.example.kido.analytics.LoginEvent;
import com.example.kido.analytics.LoginEventRepository;
import com.example.kido.analytics.ScreenView;
import com.example.kido.analytics.ScreenViewRepository;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
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

    private final ScreenViewRepository screenViews;
    private final UserRepository users;
    private final LoginEventRepository logins;
    private final MediaItemRepository items;

    public AdminActivityService(ScreenViewRepository screenViews, UserRepository users,
                                LoginEventRepository logins, MediaItemRepository items) {
        this.screenViews = screenViews;
        this.users = users;
        this.logins = logins;
        this.items = items;
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

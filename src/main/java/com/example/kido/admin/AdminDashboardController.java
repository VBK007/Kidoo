package com.example.kido.admin;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.admin.dto.DashboardDtos.ApplicationStatsDto;
import com.example.kido.admin.dto.DashboardDtos.CatalogStatsDto;
import com.example.kido.admin.dto.DashboardDtos.DashboardDto;
import com.example.kido.admin.dto.DashboardDtos.EngagementDto;
import com.example.kido.admin.dto.DashboardDtos.UserStatsDto;
import com.example.kido.user.AppUser;

/**
 * The web admin dashboard: how many people use this server, from which apps, and how
 * much there is for them to use.
 *
 * <p>Distinct from {@code /api/media/admin}, which runs one household's media server —
 * its tabs are about disks, transcodes and the profiles inside a single account. This
 * is the operator's view across every account, which is why it lives in its own
 * package and counts accounts rather than profiles.
 *
 * <p>Gated exactly as that panel is: a {@code PARENT} account <em>and</em> the
 * {@code X-Admin-Key} header. Everything here is read-only — there is no verb on this
 * controller that changes anything.
 *
 * <p>{@code GET /dashboard} returns all four sections at once, which is what a page
 * load wants. The three narrower endpoints exist for the panels a dashboard refreshes
 * on their own: a live tile polling every few seconds should not re-count the poster
 * catalog to do it.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {

    private final AdminDashboardService dashboard;
    private final AdminAccess access;

    public AdminDashboardController(AdminDashboardService dashboard, AdminAccess access) {
        this.dashboard = dashboard;
        this.access = access;
    }

    /** Users, applications, catalog and engagement in one response. */
    @GetMapping("/dashboard")
    public DashboardDto dashboard(@AuthenticationPrincipal AppUser user,
                                  @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        access.require(user, key);
        return dashboard.dashboard();
    }

    /** Account totals, signups and how many signed in recently. */
    @GetMapping("/users")
    public UserStatsDto users(@AuthenticationPrincipal AppUser user,
                              @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        access.require(user, key);
        return dashboard.userStats();
    }

    /**
     * Users per client application — Android, iOS, web — busiest first.
     *
     * <p>A person who uses two apps is counted in both rows, so these do not sum to
     * the account total. The question this answers is "how many people use the Android
     * app", not "how do the users divide up".
     */
    @GetMapping("/applications")
    public List<ApplicationStatsDto> applications(
            @AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        access.require(user, key);
        return dashboard.applications();
    }

    /** Movies, music, photos, poster templates and kids-app content, counted. */
    @GetMapping("/catalog")
    public CatalogStatsDto catalog(@AuthenticationPrincipal AppUser user,
                                   @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        access.require(user, key);
        return dashboard.catalog();
    }

    /** Live streams and watch hours — the tile worth polling on its own. */
    @GetMapping("/engagement")
    public EngagementDto engagement(@AuthenticationPrincipal AppUser user,
                                    @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        access.require(user, key);
        return dashboard.engagement();
    }
}

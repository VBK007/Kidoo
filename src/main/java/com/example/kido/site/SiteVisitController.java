package com.example.kido.site;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ClientAddress;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * The public site's own analytics intake: a beacon on the GitHub Pages landing page
 * records a view here. The admin dashboard's read side lives with the rest of the
 * admin endpoints — see {@code AdminDashboardController#siteTraffic}.
 *
 * <p>Takes no credential at all — it is fired by an anonymous browser that has no
 * token to send — so {@link SiteVisitThrottle} is what stands between it and a flood
 * script instead.
 */
@RestController
@RequestMapping("/api/site")
public class SiteVisitController {

    private final SiteVisitService visits;

    public SiteVisitController(SiteVisitService visits) {
        this.visits = visits;
    }

    @PostMapping("/visit")
    public void visit(@Valid @RequestBody RecordVisitRequest request, HttpServletRequest servletRequest) {
        String userAgent = servletRequest.getHeader("User-Agent");
        String address = ClientAddress.resolve(servletRequest);
        visits.record(address, request.path(), request.referrer(), userAgent,
                request.screenWidth(), request.screenHeight());
        // No response body either way, including when the throttle drops it: telling
        // an anonymous caller it was rate-limited is a hint worth nothing to a real
        // visitor and useful only to whoever is testing the limit.
    }
}

package com.example.kido.analytics;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.analytics.dto.LogLoginEventRequest;
import com.example.kido.analytics.dto.LoginEventDto;
import com.example.kido.user.AppUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Sign-in analytics: which device, address and (where the ingress proxy tells us)
 * rough location each login came from.
 *
 * <p>Both endpoints require a valid JWT, so a client calls {@code POST /login} right
 * after obtaining its token — the device/IP/geo describe that same sign-in.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @PostMapping("/login")
    public ResponseEntity<Void> logLogin(@AuthenticationPrincipal AppUser user,
                                         @Valid @RequestBody LogLoginEventRequest req,
                                         HttpServletRequest request) {
        service.logLogin(user, req, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /** The signed-in user's own sign-in history, most recent first. */
    @GetMapping("/login")
    public List<LoginEventDto> history(@AuthenticationPrincipal AppUser user) {
        return service.history(user).stream().map(LoginEventDto::from).toList();
    }
}

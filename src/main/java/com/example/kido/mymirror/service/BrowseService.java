package com.example.kido.mymirror.service;

import org.springframework.stereotype.Service;

import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.repo.BrowseRepository;

@Service
public class BrowseService {
    private final BrowseRepository repo;
    private final DispatcherService dispatcherService;
    private final TokenManager tokenManager;

    public BrowseService(BrowseRepository repo, DispatcherService dispatcherService, TokenManager tokenManager) {
        this.repo = repo;
        this.dispatcherService = dispatcherService;
        this.tokenManager = tokenManager;
    }

    // Catalog metadata (detail/search/episodes) is authorized by the session cookie, the same as
    // playback — not by the per-view ad-gate. So these read t straight from the cookie via playbackT,
    // never getT: getT runs verify2.php and throws 412 whenever the gate is uncleared (its normal
    // resting state), which both fails the browse call and, before the TokenManager fix, dropped the
    // whole shared session. playbackT returns the identical t token without that gate round-trip.
    public String detail(String id, String cookie) {
        return repo.getDetail(dispatcherService.resolveActiveBaseUrl(), id, tokenManager.playbackT(cookie), cookie);
    }

    public String search(String query, String cookie) {
        return repo.search(dispatcherService.resolveActiveBaseUrl(), query, tokenManager.playbackT(cookie), cookie);
    }

    public String episodes(String id, String series, String cookie) {
        return repo.episodes(dispatcherService.resolveActiveBaseUrl(), id, series, tokenManager.playbackT(cookie), cookie);
    }

    public String trailer(String id, String cookie) {
        return repo.trailer(dispatcherService.resolveActiveBaseUrl(), id, cookie);
    }
}

package com.example.kido.mymirror.service;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.repo.UserRepository;

@Service
public class UserService {
    /** The OTT skins the mirror recognises: Netflix, Prime Video, Hotstar. */
    private static final Set<String> OTT_VALUES = Set.of("nf", "pv", "hs");

    private final UserRepository repo;
    private final DispatcherService dispatcherService;

    public UserService(UserRepository repo, DispatcherService dispatcherService) {
        this.repo = repo;
        this.dispatcherService = dispatcherService;
    }

    public String addWatchlist(String id, String cookie) {
        return repo.addToWatchlist(dispatcherService.resolveActiveBaseUrl(), id, cookie);
    }

    public String recentPlay(String cookie) {
        return repo.recentPlay(dispatcherService.resolveActiveBaseUrl(), cookie);
    }

    public String addRecentPlay(String id, String cookie) {
        return repo.addRecentPlay(dispatcherService.resolveActiveBaseUrl(), id, cookie);
    }

    public String deleteRecentPlay(String id, String cookie) {
        return repo.deleteRecentPlay(dispatcherService.resolveActiveBaseUrl(), id, cookie);
    }

    public String settings(String cookie) {
        return repo.settings(dispatcherService.resolveActiveBaseUrl(), cookie);
    }

    public String setHd(String hd, String cookie) {
        return repo.setHd(dispatcherService.resolveActiveBaseUrl(), hd, cookie);
    }

    public String setOtt(String ott, String cookie) {
        if (ott == null || !OTT_VALUES.contains(ott)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ott must be one of " + OTT_VALUES);
        }
        return repo.setOtt(dispatcherService.resolveActiveBaseUrl(), ott, cookie);
    }

    public String setLanguage(String lang, String cookie) {
        return repo.setLanguage(dispatcherService.resolveActiveBaseUrl(), lang, cookie);
    }
}

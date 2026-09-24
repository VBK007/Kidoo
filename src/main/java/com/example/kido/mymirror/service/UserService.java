package com.example.kido.mymirror.service;

import org.springframework.stereotype.Service;

import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.repo.UserRepository;

@Service
public class UserService {
    private final UserRepository repo;
    private final DispatcherService dispatcherService;
    private final TokenManager tokenManager;

    public UserService(UserRepository repo, DispatcherService dispatcherService, TokenManager tokenManager) {
        this.repo = repo;
        this.dispatcherService = dispatcherService;
        this.tokenManager = tokenManager;
    }

    public String addWatchlist(String id, String cookie) {
        return repo.addToWatchlist(dispatcherService.resolveActiveBaseUrl(), id, cookie);
    }

    public String recentPlay(String cookie) {
        return repo.recentPlay(dispatcherService.resolveActiveBaseUrl(), cookie);
    }

    public String settings(String cookie) {
        return repo.settings(dispatcherService.resolveActiveBaseUrl(), cookie);
    }
}

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

    public String detail(String id, String cookie) {
        return repo.getDetail(dispatcherService.resolveActiveBaseUrl(), id, tokenManager.getT(cookie), cookie);
    }

    public String search(String query, String cookie) {
        return repo.search(dispatcherService.resolveActiveBaseUrl(), query, tokenManager.getT(cookie), cookie);
    }

    public String episodes(String id, String series, String cookie) {
        return repo.episodes(dispatcherService.resolveActiveBaseUrl(), id, series, tokenManager.getT(cookie), cookie);
    }
}

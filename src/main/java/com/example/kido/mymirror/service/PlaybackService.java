package com.example.kido.mymirror.service;

import java.net.URI;

import org.springframework.stereotype.Service;

import com.example.kido.mymirror.HlsRewriter;
import com.example.kido.mymirror.UpstreamUrl;
import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.repo.PlaybackRepository;

@Service("mirrorPlaybackService")
public class PlaybackService {
    private final PlaybackRepository repo;
    private final DispatcherService dispatcherService;
    private final TokenManager tokenManager;

    public PlaybackService(PlaybackRepository repo, DispatcherService dispatcherService, TokenManager tokenManager) {
        this.repo = repo;
        this.dispatcherService = dispatcherService;
        this.tokenManager = tokenManager;
    }

    public String resolveStream(String cookie) {
        return repo.resolveStream(dispatcherService.resolveActiveBaseUrl(), tokenManager.getHash(cookie), cookie);
    }

    public String altVideo(String cookie) {
        return repo.altVideo(dispatcherService.resolveActiveBaseUrl(), tokenManager.getHash(cookie), cookie);
    }

    public String playlist(String id, String title, String cookie) {
        return repo.fetchPlaylist(dispatcherService.resolveActiveBaseUrl(), id, title, tokenManager.getT(cookie), cookie);
    }

    /** The master playlist, with every entry made absolute so the player fetches it from the mirror. */
    public String hlsMaster(String id, String cookie) {
        String baseUrl = dispatcherService.resolveActiveBaseUrl();
        URI masterUrl = repo.hlsMasterUrl(baseUrl, id, tokenManager.getT(cookie));
        return HlsRewriter.absolutize(repo.fetchHlsMaster(baseUrl, masterUrl, cookie), masterUrl);
    }

    public String subtitle(String subtitleUrl, String cookie) {
        URI url = UpstreamUrl.requirePublicHttp(subtitleUrl);
        return repo.fetchSubtitle(url, dispatcherService.resolveActiveBaseUrl(), cookie);
    }
}

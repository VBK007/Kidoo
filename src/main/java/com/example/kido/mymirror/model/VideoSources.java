package com.example.kido.mymirror.model;

import java.util.List;

public class VideoSources {
    private List<String> urls;
    public VideoSources(List<String> urls) { this.urls = urls; }
    public List<String> getUrls() { return urls; }
}

package com.example.kido.mymirror.model;

import java.util.List;

public class HlsMaster {
    private List<String> audioTracks;
    private List<String> variants;
    public HlsMaster(List<String> audioTracks, List<String> variants) {
        this.audioTracks = audioTracks;
        this.variants = variants;
    }
    public List<String> getAudioTracks() { return audioTracks; }
    public List<String> getVariants() { return variants; }
}

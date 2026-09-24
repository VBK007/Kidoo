package com.example.kido.mymirror.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RawResponse(
        @JsonProperty("token_hash") String tokenHash,
        String doms,
        String mwin,
        String popwin,
        String var,
        String stape,
        List<String> u) {
}

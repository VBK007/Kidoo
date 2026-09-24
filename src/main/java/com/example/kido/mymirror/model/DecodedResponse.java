package com.example.kido.mymirror.model;

import java.util.List;

public record DecodedResponse(String tokenHash, String stape, List<String> urls,
                              String doms, String mwin, String popwin, String version) {
}

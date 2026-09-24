package com.example.kido.mymirror.model;

import java.util.List;

/** What the extractor pulls out of a page: element ids, image sources and titles. */
public record PageContent(List<String> ids, List<String> images, List<String> titles) {
}

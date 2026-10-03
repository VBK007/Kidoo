package com.example.kido.site;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param path         the page path the beacon fired from, e.g. {@code /} or {@code /browse}
 * @param referrer     {@code document.referrer} from the browser, blank for a direct visit
 * @param screenWidth  {@code window.screen.width}, null if the beacon didn't send one
 * @param screenHeight {@code window.screen.height}, null if the beacon didn't send one
 */
public record RecordVisitRequest(
        @NotBlank @Size(max = 512) String path,
        @Size(max = 512) String referrer,
        Integer screenWidth,
        Integer screenHeight) {}

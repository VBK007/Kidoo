package com.example.kido.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class RequestLoggingFilterTest {
    @Test
    void mirrorQueriesAreOmittedIncludingNestedAndEncodedUrls() {
        assertEquals("", RequestLoggingFilter.queryForLogging("/playback/variant", "%75=fixture&s=fixture"));
        assertEquals("", RequestLoggingFilter.queryForLogging("/playback/subtitle", "url=https%3A%2F%2Fexample.test"));
        assertEquals("", RequestLoggingFilter.queryForLogging("/browse/detail", "id=42&cookie=fixture"));
        assertEquals("?token=***&room=42", RequestLoggingFilter.queryForLogging("/ws/party", "token=fixture&room=42"));
    }
}

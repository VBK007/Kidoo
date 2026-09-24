package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;
import com.example.kido.mymirror.service.MirrorHomeService;

/** /api/media/home carries the mirror rail for a parent who sends a mirror session, and only then. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MirrorHomeEndpointIntegrationTest {

    @LocalServerPort
    int port;

    @MockitoBean
    MirrorHomeService mirrorHome;

    private final HttpClient http = HttpClient.newHttpClient();

    private static final MirrorRailDto RAIL = new MirrorRailDto("mirror", "From the mirror",
            List.of(new MirrorTileDto("111", "Alpha", "https://img.example/111.jpg", null)));

    @Test
    void aParentWithAMirrorSessionGetsBothTheLibraryAndTheMirror() throws Exception {
        when(mirrorHome.rail("sess=1")).thenReturn(Optional.of(RAIL));
        String[] account = account("PARENT");

        HttpResponse<String> home = home(account, "sess=1");

        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"rails\""), home.body());
        assertTrue(home.body().contains("\"mirror\":{\"key\":\"mirror\""), home.body());
        assertTrue(home.body().contains("\"id\":\"111\""), home.body());
    }

    @Test
    void withoutAMirrorSessionTheHomeScreenIsUnchanged() throws Exception {
        when(mirrorHome.rail(null)).thenReturn(Optional.empty());

        HttpResponse<String> home = home(account("PARENT"), null);

        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"mirror\":null"), home.body());
    }

    @Test
    void aChildAccountNeverSeesTheMirror() throws Exception {
        when(mirrorHome.rail(any())).thenReturn(Optional.of(RAIL));

        HttpResponse<String> home = home(account("CHILD"), "sess=1");

        assertEquals(200, home.statusCode(), home.body());
        assertFalse(home.body().contains("\"id\":\"111\""), home.body());
        verify(mirrorHome, never()).rail(any());
    }

    // ---- helpers ----

    private HttpResponse<String> home(String[] account, String mirrorCookie) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/media/home"))
                .header("Authorization", "Bearer " + account[0]);
        if (account[1] != null) req.header("X-Profile-Id", account[1]);
        if (mirrorCookie != null) req.header("X-Mirror-Cookie", mirrorCookie);
        return http.send(req.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Bearer token and profile id for a fresh account of the given role. */
    private String[] account(String role) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = post("/api/auth/register", """
                {"username":"mh_%s","email":"mh_%s@example.com","password":"pw123456",
                 "displayName":"Mirror Home","role":"%s"}
                """.formatted(unique, unique, role), null);
        assertEquals(201, registered.statusCode(), registered.body());
        String bearer = extract(registered.body(), "token");
        HttpResponse<String> profile = post("/api/profiles", "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", bearer);
        return new String[] {bearer, profile.statusCode() == 201 ? extract(profile.body(), "id") : null};
    }

    private HttpResponse<String> post(String path, String json, String bearer) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        return http.send(req.POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extract(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}

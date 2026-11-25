package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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

/** /api/media/home carries the mirror rail for any signed-in account, filled from the caller's own
 *  mirror session when it sends one or the server's global session otherwise. */
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
        when(mirrorHome.railWithStatus("sess=1", null)).thenReturn(new MirrorHomeService.RailResult(List.of(RAIL), "available"));
        String[] account = account("PARENT");

        HttpResponse<String> home = home(account, "sess=1");

        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"rails\""), home.body());
        assertTrue(home.body().contains("\"mirror\":[{\"key\":\"mirror\""), home.body());
        assertTrue(home.body().contains("\"id\":\"111\""), home.body());
    }

    @Test
    void withoutAMirrorSessionTheHomeScreenIsUnchanged() throws Exception {
        when(mirrorHome.railWithStatus(null, null)).thenReturn(new MirrorHomeService.RailResult(List.of(), "missing"));

        HttpResponse<String> home = home(account("PARENT"), null);

        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"mirror\":null"), home.body());
    }

    @Test
    void aChildAccountAlsoSeesTheMirror() throws Exception {
        // The mirror catalogue has no age filtering; this build composes the rail for children too
        // (a deliberate choice recorded on HomeController#home), so the gate is signed-in, not parent.
        when(mirrorHome.railWithStatus("sess=1", null)).thenReturn(new MirrorHomeService.RailResult(List.of(RAIL), "available"));

        HttpResponse<String> home = home(account("CHILD"), "sess=1");

        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"id\":\"111\""), home.body());
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

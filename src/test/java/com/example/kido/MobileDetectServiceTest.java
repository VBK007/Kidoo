package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.MobileDetectService;
import com.example.kido.mymirror.model.ClientHeaders;
import com.example.kido.mymirror.model.PageContent;
import com.example.kido.mymirror.repo.MobileDetectRepository;

/**
 * The service against a canned page, with no network: the repository is replaced by a
 * stub that records what it was asked for and hands back fixed HTML.
 */
class MobileDetectServiceTest {

    private static final ClientHeaders HEADERS = new ClientHeaders("TestAgent/1.0", "text/html", "ta-IN");

    /** Returns {@code html} and remembers the last call's arguments. */
    private static final class StubRepository extends MobileDetectRepository {
        private final String html;
        String app;
        ClientHeaders client;
        int calls;

        StubRepository(String html) {
            this.html = html;
        }

        @Override
        public String fetchHomePage(String appParam, ClientHeaders client) {
            this.app = appParam;
            this.client = client;
            this.calls++;
            return html;
        }
    }

    @Test
    void extractsIdsImagesAndTitles() {
        StubRepository repo = new StubRepository("""
                <html><head><title>Home</title></head>
                <body>
                  <div id="hero"><img src="https://cdn.example/a.jpg"></div>
                  <section id="row-1"><img src="https://cdn.example/b.png"><img alt="no source"></section>
                </body></html>
                """);

        PageContent content = new MobileDetectService(repo).extractPageContent("1", HEADERS);

        assertEquals(List.of("hero", "row-1"), content.ids());
        assertEquals(List.of("https://cdn.example/a.jpg", "https://cdn.example/b.png"), content.images());
        assertEquals(List.of("Home"), content.titles());
    }

    @Test
    void keepsRelativeImageSourcesAsGiven() {
        // Jsoup.parse(html) has no base URI, so a relative src cannot be made absolute.
        StubRepository repo = new StubRepository("<img src=\"/poster/7.jpg\">");

        PageContent content = new MobileDetectService(repo).extractPageContent("1", HEADERS);

        assertEquals(List.of("/poster/7.jpg"), content.images());
    }

    @Test
    void passesTheUsersAppAndHeadersThrough() {
        StubRepository repo = new StubRepository("<html></html>");

        new MobileDetectService(repo).extractPageContent("kids_2", HEADERS);

        assertEquals("kids_2", repo.app);
        assertSame(HEADERS, repo.client);
    }

    @Test
    void emptyOrMissingPageGivesEmptyContent() {
        for (String html : new String[] {null, ""}) {
            PageContent content = new MobileDetectService(new StubRepository(html)).extractPageContent("1", HEADERS);

            assertTrue(content.ids().isEmpty());
            assertTrue(content.images().isEmpty());
            assertTrue(content.titles().isEmpty());
        }
    }

    @Test
    void refusesABadAppWithoutCallingUpstream() {
        for (String app : new String[] {null, "", "1&x=2", "a b", "../home", "x".repeat(33)}) {
            StubRepository repo = new StubRepository("<html></html>");
            MobileDetectService service = new MobileDetectService(repo);

            ApiException ex = assertThrows(ApiException.class, () -> service.extractPageContent(app, HEADERS),
                    "app=" + app);
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(0, repo.calls, "upstream must not be hit for app=" + app);
            assertNull(repo.app);
        }
    }

    @Test
    void acceptsTheLongestAllowedApp() {
        StubRepository repo = new StubRepository("<html></html>");

        new MobileDetectService(repo).extractPageContent("x".repeat(32), HEADERS);

        assertEquals(1, repo.calls);
    }
}

package com.example.kido.mymirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.example.kido.mymirror.repo.AdGateRepository;
import com.example.kido.mymirror.service.DispatcherService;

/**
 * The keep-alive poll that stops an earned session going cold: it re-POSTs {@code verify2.php} on a
 * timer the way the real app's home WebView does, and keeps the genuinely-earned session warm. An
 * uncleared ({@code c:n}, "Waiting for your ads click.") answer is the gate's normal resting state
 * once an ad-view's cleared window lapses — not proof the cookie died, which still authorizes content
 * past that point — so the poll keeps the session on it, exactly as on a cleared answer and on a
 * transient failure. A truly dead session is dropped elsewhere by unambiguous signals (age-based
 * rotation, upstream rejection), never by this ambiguous poll. Spawning is off throughout, so the
 * poll re-earns nothing here; it only reads the gate and keeps the held cookie.
 */
class MirrorSessionKeepAliveTest {

    private static final String BASE = "https://mirror.example";
    // A well-formed held session: the file must carry a t_hash_t marker, and addhash carries the
    // URL-encoded 4-part verify token (addhash::t_hash::ts::ni) the poll re-sends.
    private static final String SESSION =
            "t_hash_t=abc%3A%3Adef%3A%3A123%3A%3Ani%3A%3Am; addhash=abc%3A%3Adef%3A%3A123%3A%3Ani";

    /** A dispatcher that answers {@link #BASE} without any network. */
    private static final class FixedDispatcher extends DispatcherService {
        FixedDispatcher() {
            super(null);
        }

        @Override
        public String resolveActiveBaseUrl() {
            return BASE;
        }
    }

    private static MirrorSessionProvider providerHolding(RestTemplate rest, Path file) throws IOException {
        Files.writeString(file, SESSION, StandardCharsets.UTF_8);
        MirrorSessionProperties props = new MirrorSessionProperties();
        props.setSessionFile(file.toString());
        MirrorSessionProvider provider = new MirrorSessionProvider(
                props, new MirrorAccessProperties(), new AdGateRepository(rest), new FixedDispatcher());
        provider.load(); // @PostConstruct in production; called directly to seed the held session
        return provider;
    }

    @Test
    void keepsAClearedSession(@TempDir Path dir) throws IOException {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"c\":\"y\",\"statusup\":\"OK\"}", MediaType.APPLICATION_JSON));

        MirrorSessionProvider provider = providerHolding(rest, dir.resolve("session.txt"));
        assertEquals(SESSION, provider.currentCookie());

        provider.keepSessionWarm();

        assertEquals(SESSION, provider.currentCookie(), "a cleared gate must keep the session");
        server.verify();
    }

    @Test
    void keepsASessionWhenTheGateIsAtItsRestingUnclearedState(@TempDir Path dir) throws IOException {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        // The gate's normal resting answer once an ad-view's cleared window lapses. It is NOT proof the
        // cookie died — a genuinely-earned session keeps authorizing content well past this point — and
        // the poll cannot tell a resting session from a dead one, so c:n is not a safe drop signal.
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"c\":\"n\",\"statusup\":\"Waiting for your ads click.\"}",
                        MediaType.APPLICATION_JSON));

        MirrorSessionProvider provider = providerHolding(rest, dir.resolve("session.txt"));

        provider.keepSessionWarm();

        assertEquals(SESSION, provider.currentCookie(),
                "a resting uncleared gate must keep the earned session, not strand the app at 503 every poll; "
                        + "a truly dead session is dropped by age rotation / upstream rejection, not by this poll");
        server.verify();
    }

    @Test
    void keepsTheSessionOnATransientFailure(@TempDir Path dir) throws IOException {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        MirrorSessionProvider provider = providerHolding(rest, dir.resolve("session.txt"));

        provider.keepSessionWarm();

        assertEquals(SESSION, provider.currentCookie(), "a blip must not throw away a good session");
        server.verify();
    }
}

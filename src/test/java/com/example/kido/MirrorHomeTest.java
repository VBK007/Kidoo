package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;
import com.example.kido.mymirror.MirrorHomeParser;
import com.example.kido.mymirror.MirrorHomeParser.Title;
import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.repo.AdGateRepository;
import com.example.kido.mymirror.repo.BrowseRepository;
import com.example.kido.mymirror.repo.PlaybackRepository;
import com.example.kido.mymirror.service.DispatcherService;
import com.example.kido.mymirror.service.MirrorHomeService;
import com.example.kido.mymirror.service.PlaybackService;

/** The mirror's home page joined with playlist.php, against a scripted upstream. */
class MirrorHomeTest {

    private static final String BASE = "https://mirror.example";

    // ---- Parser ----

    @Test
    void readsTilesByDataAttributeThenByPosterFileName() {
        List<Title> titles = MirrorHomeParser.parse("""
                <body>
                  <article class="post" data-post="111"><img data-src="/poster/v/111.jpg" src="data:image/gif;base64,x" alt="Alpha"></article>
                  <div data-id="222" title="Beta"><img src="https://img.example/b.webp"></div>
                  <article data-post="111"><img src="/dup.jpg"></article>
                  <img src="https://img.example/poster/v/333.jpg" alt="Gamma">
                  <img src="https://img.example/logo.png">
                  <div data-post="bad id!"><img src="/x.jpg"></div>
                </body>""", BASE);

        assertEquals(List.of(
                new Title("111", "Alpha", BASE + "/poster/v/111.jpg"),
                new Title("222", "Beta", "https://img.example/b.webp"),
                new Title("333", "Gamma", "https://img.example/poster/v/333.jpg")), titles);
    }

    @Test
    void anEmptyPageHasNoTitles() {
        assertTrue(MirrorHomeParser.parse(null, BASE).isEmpty());
        assertTrue(MirrorHomeParser.parse("<html></html>", BASE).isEmpty());
    }

    // ---- Service ----

    private static final class FixedDispatcher extends DispatcherService {
        FixedDispatcher() {
            super(null, null);
        }

        @Override
        public String resolveActiveBaseUrl() {
            return BASE;
        }
    }

    private static MirrorHomeService service(RestTemplate rest) {
        DispatcherService dispatcher = new FixedDispatcher();
        TokenManager tokens = new TokenManager(rest, dispatcher, new AdGateRepository(rest));
        PlaybackService playback = new PlaybackService(new PlaybackRepository(rest), dispatcher, tokens);
        return new MirrorHomeService(new BrowseRepository(rest), playback, dispatcher, tokens);
    }

    private static final String HOME = """
            <html><body data-time="T1" data-hash="H1">
              <article data-post="111"><img src="/p/111.jpg" alt="Alpha"></article>
              <article data-post="222"><img src="/p/222.jpg" alt="Beta"></article>
            </body></html>""";

    @Test
    void eachTitleComesBackWithItsPlaylist_andALatePlaylistIsJustLeftOff() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php"))
                .andRespond(withSuccess("{\"statusup\":\"OK\"}", MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(BASE + "/mobile/home?app=1"))
                .andRespond(withSuccess(HOME, MediaType.TEXT_HTML));
        server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=111&t=Alpha&tm=T1"))
                .andRespond(withSuccess("[{\"file\":\"/hls/111.m3u8\"}]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=222&t=Beta&tm=T1"))
                .andRespond(withServerError());

        Optional<MirrorRailDto> rail = service(rest).rail("sess=1");

        assertTrue(rail.isPresent());
        List<MirrorTileDto> tiles = rail.get().items();
        assertEquals(2, tiles.size());
        assertEquals("111", tiles.get(0).id());
        assertEquals("Alpha", tiles.get(0).title());
        assertEquals(BASE + "/p/111.jpg", tiles.get(0).image());
        assertEquals("/hls/111.m3u8", tiles.get(0).playlist().get(0).path("file").asString());
        assertEquals("222", tiles.get(1).id());
        assertNull(tiles.get(1).playlist()); // failed upstream: tile kept, playlist fetched on tap
    }

    @Test
    void aMirrorThatIsDownMeansNoRailRatherThanABrokenHomeScreen() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(BASE + "/mobile/verify2.php")).andRespond(withServerError());

        assertTrue(service(rest).rail("sess=1").isEmpty());
    }

    @Test
    void noCookieMeansNoMirrorCalls() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();

        assertTrue(service(rest).rail(null).isEmpty());
        assertTrue(service(rest).rail(" ").isEmpty());
        server.verify(); // no expectations, so any call would have failed
    }
}

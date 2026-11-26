package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;
import com.example.kido.mymirror.MirrorHomeParser;
import com.example.kido.mymirror.MirrorHomeParser.Title;
import com.example.kido.mymirror.MirrorStreamSigner;
import com.example.kido.mymirror.MirrorRequestLimit;
import com.example.kido.mymirror.MirrorSessionProperties;
import com.example.kido.mymirror.MirrorSessionProvider;
import com.example.kido.mymirror.catalogue.MirrorCatalogueProperties;
import com.example.kido.mymirror.catalogue.MirrorCatalogueStore;
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

    private static Title parse1(String body, String ott) {
        return MirrorHomeParser.parseHome(body, ott).get(0);
    }

    @Test
    void readsIdsAndCategoriesFromTheNetflixHomeAndDropsRepeatsAndBadIds() {
        List<Title> titles = MirrorHomeParser.parseHome("""
                <div class="top10-post post-data" data-post="999"></div>
                <h2 class="tray-title">Trending Now</h2>
                <div class="top10-posts lazyload">
                  <div class="top10-post post-data" data-post="82787383"></div>
                  <div class="top10-post post-data" data-post="222"></div>
                  <div class="top10-post post-data" data-post="82787383"></div>
                  <div class="top10-post post-data" data-post="bad id!"></div>
                  <div class="top10-post post-data" data-post=""></div>
                </div>
                <h2 class="tray-title">Romance Movies</h2>
                <div class="top10-posts lazyload">
                  <div class="top10-post post-data" data-post="555"></div>
                </div>""", "nf");

        // The tile above the first heading keeps no category; the rest take the nearest heading above
        // them. The dup and the bad/blank ids drop. Every poster is rebuilt from the numeric Netflix id.
        assertEquals(List.of(
                new Title("999", null, "https://imgcdn.kim/nf/v/200/999.jpg", null),
                new Title("82787383", null, "https://imgcdn.kim/nf/v/200/82787383.jpg", "Trending Now"),
                new Title("222", null, "https://imgcdn.kim/nf/v/200/222.jpg", "Trending Now"),
                new Title("555", null, "https://imgcdn.kim/nf/v/200/555.jpg", "Romance Movies")), titles);
    }

    @Test
    void theNetflixHomePosterRebuildsFromTheNumericIdOnly() {
        // A numeric Netflix id rebuilds the CDN poster; a non-numeric id has no Netflix path, so there
        // is nothing to reconstruct and the poster is left null.
        assertEquals("https://imgcdn.kim/nf/v/200/81073593.jpg",
                parse1("<div data-post=\"81073593\"></div>", "nf").image());
        assertNull(parse1("<div data-post=\"0RTZ57DQ6PBHH29UN5JS7U7CW4\"></div>", "nf").image());
        // The default home load carries no ott but is still Netflix, so the numeric id rebuilds too.
        assertEquals("https://imgcdn.kim/nf/v/200/81073593.jpg",
                parse1("<div data-post=\"81073593\"></div>", "").image());
    }

    @Test
    void theHotstarHomeSharesTheHtmlFeedAndRebuildsTheHsPoster() {
        // Hotstar (the upstream `dp` catalogue) rides the same /mobile/home?app=1 HTML as Netflix,
        // switched by the ott cookie; its tiles carry long numeric ids and its posters live under the
        // hs/v/166 CDN namespace. A non-numeric id has no Hotstar path, so its poster stays null.
        Title t = parse1("<h2 class=\"tray-title\">Trending</h2><div data-post=\"1260023080\"></div>", "dp");
        assertEquals("https://imgcdn.kim/hs/v/166/1260023080.jpg", t.image());
        assertEquals("Trending", t.category());
        assertNull(parse1("<div data-post=\"0RTZ57DQ6PBHH29UN5JS7U7CW4\"></div>", "dp").image());
    }

    @Test
    void buildsThePrimePosterFromTheHomepageIds() {
        List<Title> titles = MirrorHomeParser.parseHome("""
                {"slider":[{"id":"0RTZ57DQ6PBHH29UN5JS7U7CW4"}],
                 "post":[{"ids":"0F73PDQ6ZQ49MYAFRLDRTKF4P1","cate":"Top 10 Movies in Prime"}]}""", "pv");

        // The slider tile carries no category; the post row's tiles carry its "cate" so the service
        // can split them onto their own rail. Neither carries a name — that is looked up later.
        assertEquals(List.of(
                new Title("0RTZ57DQ6PBHH29UN5JS7U7CW4", null, "https://imgcdn.kim/pv/341/0RTZ57DQ6PBHH29UN5JS7U7CW4.jpg", null),
                new Title("0F73PDQ6ZQ49MYAFRLDRTKF4P1", null, "https://imgcdn.kim/pv/341/0F73PDQ6ZQ49MYAFRLDRTKF4P1.jpg", "Top 10 Movies in Prime")),
                titles);
    }

    @Test
    void anEmptyHomeHasNoTitles() {
        assertTrue(MirrorHomeParser.parseHome(null, "nf").isEmpty());
        assertTrue(MirrorHomeParser.parseHome("", "nf").isEmpty());
        assertTrue(MirrorHomeParser.parseHome("   ", "nf").isEmpty());
        // Netflix HTML with no tiles, and Prime JSON with empty collections, both yield nothing.
        assertTrue(MirrorHomeParser.parseHome("<html><body></body></html>", "nf").isEmpty());
        assertTrue(MirrorHomeParser.parseHome("{}", "pv").isEmpty());
        assertTrue(MirrorHomeParser.parseHome("{\"slider\":[],\"post\":[]}", "pv").isEmpty());
    }

    // ---- Service ----

    private static final class FixedDispatcher extends DispatcherService {
        FixedDispatcher() {
            super(null);
        }

        @Override
        public String resolveActiveBaseUrl() {
            return BASE;
        }
    }

    private static MirrorHomeService service(RestTemplate rest) {
        DispatcherService dispatcher = new FixedDispatcher();
        // Spawning is off and no @PostConstruct runs, so this provider holds no global session and
        // never opens a browser; it only satisfies TokenManager's dependency.
        AdGateRepository adGate = new AdGateRepository(rest);
        MirrorSessionProvider sessionProvider = new MirrorSessionProvider(
                new MirrorSessionProperties(), adGate, dispatcher);
        TokenManager tokens = new TokenManager(rest, dispatcher, adGate, sessionProvider);
        PlaybackService playback = new PlaybackService(new PlaybackRepository(rest, rest, rest), dispatcher, tokens,
                new MirrorStreamSigner(), new MirrorRequestLimit(), sessionProvider);
        // Catalogue persistence is off by default, so publish/read/nextSequence all short-circuit before
        // touching a repository — null repos are never dereferenced in this configuration.
        MirrorCatalogueStore catalogueStore = new MirrorCatalogueStore(null, null, null, new MirrorCatalogueProperties());
        return new MirrorHomeService(new BrowseRepository(rest), playback, dispatcher, tokens, sessionProvider,
                catalogueStore);
    }

    // Netflix serves its categorised home as HTML at /mobile/home?app=1: one <h2 class="tray-title">
    // per rail, its tiles carrying only a data-post id. This golden is a single-category home.
    private static final String TRENDING = BASE + "/mobile/search.php";
    private static final String TRENDING_JSON = """
            {"searchResult":[{"id":"111","t":"Alpha"},{"id":"222","t":"Beta"}]}
            """;

    private static void netflixDetails(MockRestServiceServer server, String details) {
        server.expect(manyTimes(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/mobile/post.php")))
                .andRespond(withSuccess(details, MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(org.hamcrest.Matchers.startsWith(TRENDING + "?")))
                .andRespond(withSuccess("{\"searchResult\":[]}", MediaType.APPLICATION_JSON));
    }

    @Test
    void netflixTilesKeepInlineNamesAndDeferAllPlaylists() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        server.expect(once(), requestTo(TRENDING))
                .andRespond(withSuccess(TRENDING_JSON, MediaType.APPLICATION_JSON));
        netflixDetails(server, "{}");

        List<MirrorRailDto> rails = service(rest).rail("sess=1");
        assertEquals(1, rails.size());
        assertEquals("top-10-this-week", rails.get(0).key());
        List<MirrorTileDto> tiles = rails.get(0).items();
        assertEquals(List.of("111", "222"), tiles.stream().map(MirrorTileDto::id).toList());
        assertEquals(List.of("Alpha", "Beta"), tiles.stream().map(MirrorTileDto::title).toList());
        tiles.forEach(tile -> assertNull(tile.playlist()));
        server.verify();
    }

    @Test
    void theNetflixHomeComposesTrendingAndGenreRails() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        server.expect(once(), requestTo(TRENDING)).andRespond(withSuccess("""
                {"searchResult":[{"id":"111","t":"Alpha"},{"id":"222","t":"Beta"},
                 {"id":"333","t":"Gamma"},{"id":"444","t":"Delta"}]}
                """, MediaType.APPLICATION_JSON));
        netflixDetails(server, "{\"genre\":\"Action\"}");

        List<MirrorRailDto> rails = service(rest).rail("sess=1", "nf");
        assertEquals(List.of("top-10-this-week", "action-adventure"), rails.stream().map(MirrorRailDto::key).toList());
        for (MirrorRailDto rail : rails) {
            assertEquals(List.of("111", "222", "333", "444"), rail.items().stream().map(MirrorTileDto::id).toList());
        }
        server.verify();
    }

    @Test
    void theNetflixCatalogueFillsPostersFromTheTileIds() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        server.expect(once(), requestTo(TRENDING))
                .andRespond(withSuccess(TRENDING_JSON, MediaType.APPLICATION_JSON));
        netflixDetails(server, "{}");

        List<MirrorRailDto> rails = service(rest).rail("sess=1", "nf");
        assertEquals(1, rails.size());
        List<MirrorTileDto> tiles = rails.get(0).items();
        assertEquals("https://imgcdn.kim/nf/v/200/111.jpg", tiles.get(0).image());
        assertEquals("https://imgcdn.kim/nf/v/200/222.jpg", tiles.get(1).image());
        server.verify();
    }

    @Test
    void thePrimeFeedBecomesOneRailPerCategoryWithTheFeaturedStripFirst() {
        // Prime's homepage carries a featured slider (no category) and "post" rows each with their
        // own "cate". The slider's tiles fall into the single "From the mirror" rail; each post row
        // becomes its own rail, keyed by the slug of its cate, in feed order.
        String feed = """
                {"slider":[{"id":"0RTZ57DQ6PBHH29UN5JS7U7CW4"}],
                 "post":[
                   {"ids":"0F73PDQ6ZQ49MYAFRLDRTKF4P1,0F73PDQ6ZQ49MYAFRLDRTKF4P2","cate":"Top 10 Movies in Prime"},
                   {"ids":"0NNFEPONQISILYY9A5UGZML3NE","cate":"Top 10 TV in Prime"}]}""";

        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        server.expect(once(), requestTo(BASE + "/pv/homepage.php")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(feed, MediaType.APPLICATION_JSON));
        // Prime names nothing in the feed, so each tile's name comes from /pv/post.php.
        server.expect(manyTimes(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/pv/post.php")))
                .andRespond(withSuccess("{\"title\":\"A Prime Title\"}", MediaType.APPLICATION_JSON));

        List<MirrorRailDto> rails = service(rest).rail("sess=1", "pv");

        assertEquals(3, rails.size());
        // The featured slider first, as its own "From the mirror" rail.
        assertEquals("mirror", rails.get(0).key());
        assertEquals("From the mirror", rails.get(0).title());
        assertEquals(List.of("0RTZ57DQ6PBHH29UN5JS7U7CW4"), rails.get(0).items().stream().map(MirrorTileDto::id).toList());
        assertEquals("https://imgcdn.kim/pv/341/0RTZ57DQ6PBHH29UN5JS7U7CW4.jpg", rails.get(0).items().get(0).image());
        // Then the two "cate" rows, in feed order, each keyed by its slug.
        assertEquals("top-10-movies-in-prime", rails.get(1).key());
        assertEquals("Top 10 Movies in Prime", rails.get(1).title());
        assertEquals(List.of("0F73PDQ6ZQ49MYAFRLDRTKF4P1", "0F73PDQ6ZQ49MYAFRLDRTKF4P2"),
                rails.get(1).items().stream().map(MirrorTileDto::id).toList());
        assertEquals("top-10-tv-in-prime", rails.get(2).key());
        assertEquals("Top 10 TV in Prime", rails.get(2).title());
        assertEquals(1, rails.get(2).items().size());
        rails.forEach(rail -> rail.items().forEach(tile -> assertNull(tile.playlist())));
        server.verify();
    }

    @Test
    void homeNeverFetchesPlaybackPlaylistsEvenForTheFirstTile() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        server.expect(once(), requestTo(BASE + "/pv/homepage.php"))
                .andRespond(withSuccess("""
                        {"post":[{"ids":"A1,A2,A3","cate":"Drama"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(times(3), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/pv/post.php")))
                .andRespond(withSuccess("{\"title\":\"Prime Title\"}", MediaType.APPLICATION_JSON));

        var tiles = service(rest).rail("sess=1", "pv").get(0).items();
        assertEquals(List.of("A1", "A2", "A3"), tiles.stream().map(MirrorTileDto::id).toList());
        tiles.forEach(tile -> assertNull(tile.playlist()));
        server.verify();
    }

    @Test
    void aDeepCategoryIsCappedPerRailWhileEveryCategorySurvives() {
        // A feed whose first category is far deeper than TILES_PER_RAIL, with a one-title "Rare Row"
        // after it. The old whole-grid cap filled its slots in feed order, so a deep lead category
        // soaked them all and left the rest a single tile each (the one-tile-per-rail symptom). The
        // per-rail cap instead holds the deep row to TILES_PER_RAIL and gives every other category its
        // own tiles, so the lead row is trimmed and the late "Rare Row" keeps its title.
        int deep = MirrorHomeService.TILES_PER_RAIL * 3; // well past the per-rail cap on its own
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < deep; i++) {
            if (i > 0) ids.append(',');
            ids.append(String.format("BIG%03d", i));
        }
        String feed = """
                {"slider":[],
                 "post":[
                   {"ids":"%s","cate":"Big Row"},
                   {"ids":"RARE01","cate":"Rare Row"}]}""".formatted(ids);

        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        server.expect(once(), requestTo(BASE + "/pv/homepage.php")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(feed, MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/pv/post.php")))
                .andRespond(withSuccess("{\"title\":\"A Prime Title\"}", MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/mobile/pv/playlist.php")))
                .andRespond(withSuccess("[{\"file\":\"/hls/x.m3u8\"}]", MediaType.APPLICATION_JSON));

        List<MirrorRailDto> rails = service(rest).rail("sess=1", "pv");

        // The deep row is held to the per-rail cap, not allowed to soak the whole grid ...
        MirrorRailDto big = rails.stream().filter(r -> "big-row".equals(r.key())).findFirst().orElse(null);
        assertNotNull(big, "the deep category must still become a rail");
        assertEquals(MirrorHomeService.TILES_PER_RAIL, big.items().size());
        // ... and the one-title category beside it keeps its tile rather than being starved to nothing.
        MirrorRailDto rare = rails.stream().filter(r -> "rare-row".equals(r.key())).findFirst().orElse(null);
        assertNotNull(rare, "the second category must still become a rail with its own tile");
        assertEquals(List.of("RARE01"), rare.items().stream().map(MirrorTileDto::id).toList());
    }

    @Test
    void aTransientFeedFailureIsRetriedOnAFreshlyResolvedHost() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        server.expect(once(), requestTo(TRENDING)).andRespond(withServerError());
        server.expect(once(), requestTo(TRENDING))
                .andRespond(withSuccess(TRENDING_JSON, MediaType.APPLICATION_JSON));
        netflixDetails(server, "{}");

        List<MirrorRailDto> rails = service(rest).rail("sess=1");
        assertEquals(1, rails.size());
        assertEquals(List.of("111", "222"), rails.get(0).items().stream().map(MirrorTileDto::id).toList());
        server.verify();
    }

    @Test
    void aMirrorThatIsDownMeansNoRailRatherThanABrokenHomeScreen() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(times(2), requestTo(TRENDING)).andRespond(withServerError());
        assertTrue(service(rest).rail("sess=1").isEmpty());
        server.verify();
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

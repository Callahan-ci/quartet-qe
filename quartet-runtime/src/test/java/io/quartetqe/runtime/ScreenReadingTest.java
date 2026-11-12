package io.quartetqe.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The facts a real shop page needs: how many things are listed, and what only a CSS class says, such as a star rating. */
class ScreenReadingTest {
    private static final String PAGE = """
        <html><body><h1>Books</h1>
        <article class="product_pod"><h3><a href="/a" title="A Light in the Attic">A Light...</a></h3><p class="star-rating Three">stars</p><button>Add to basket</button></article>
        <article class="product_pod"><h3><a href="/b" title="Tipping the Velvet">Tipping...</a></h3><p class="star-rating One">stars</p><button>Add to basket</button></article>
        <article class="product_pod"><h3><a href="/c" title="Soumission">Soumission</a></h3><p class="star-rating Four">stars</p><button>Add to basket</button></article>
        <img src="x.png" alt="Shop logo">
        </body></html>""";

    private HttpServer server;
    private BrowserSurface surface;
    private final Map<String, Tool> tools = new HashMap<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        surface = new BrowserSurface("http://127.0.0.1:" + server.getAddress().getPort(),
                Json.parse("{\"engine\":\"chromium\",\"preset\":\"desktop\"}"), true, host -> true);
        new ScreenDriver(surface).tools().forEach(tool -> tools.put(tool.name(), tool));
        tools.get("screen_open").handler().call(Json.parse("{\"path\":\"/\"}"));
    }

    @AfterEach
    void stop() throws Exception {
        surface.close();
        server.stop(0);
    }

    private JsonNode call(String tool, String args) throws Exception {
        return tools.get(tool).handler().call(Json.parse(args));
    }

    @Test
    void controlsAreCountedByTheToolSoNobodyCountsByEye() throws Exception {
        JsonNode buttons = call("screen_count", "{\"role\":\"button\",\"nameContains\":\"add to basket\"}");
        assertThat(buttons.path("count").asInt()).isEqualTo(3);
        assertThat(buttons.path("replayCheck").asText()).isEqualTo("count:some");

        assertThat(call("screen_count", "{\"role\":\"heading\"}").path("count").asInt()).isEqualTo(4);
        JsonNode none = call("screen_count", "{\"nameContains\":\"checkout\"}");
        assertThat(none.path("count").asInt()).isZero();
        assertThat(none.path("replayCheck").asText()).isEqualTo("count:none");
        assertThatThrownBy(() -> call("screen_count", "{}")).hasMessageContaining("role, nameContains or both");
    }

    @Test
    void aRatingKeptOnlyInACssClassCanBeReadWithTheClassWordsSeparated() throws Exception {
        JsonNode ratings = call("screen_query", "{\"selector\":\"p.star-rating\",\"attributes\":[\"class\"],\"limit\":2}");

        assertThat(ratings.path("count").asInt()).isEqualTo(3);
        assertThat(ratings.path("attributes").size()).as("only the first two are read in detail").isEqualTo(2);
        assertThat(ratings.path("attributes").get(0).path("class").asText()).isEqualTo("star-rating Three");
        assertThat(ratings.path("attributes").get(0).path("classes").toString()).isEqualTo("[\"star-rating\",\"Three\"]");
        assertThat(ratings.path("attributes").get(1).path("classes").get(1).asText()).isEqualTo("One");
        assertThat(ratings.path("replayCheck").asText()).isEqualTo("count:some");
    }

    @Test
    void textAndOtherAttributesAreReadToo() throws Exception {
        JsonNode links = call("screen_query", "{\"selector\":\"article h3 a\",\"attributes\":[\"title\",\"href\"]}");
        assertThat(links.path("count").asInt()).isEqualTo(3);
        assertThat(links.path("texts").get(2).asText()).isEqualTo("Soumission");
        assertThat(links.path("attributes").get(0).path("title").asText()).isEqualTo("A Light in the Attic");
        assertThat(links.path("attributes").get(1).path("href").asText()).isEqualTo("/b");

        assertThat(call("screen_query", "{\"selector\":\"img\",\"attributes\":[\"alt\"]}").path("attributes").get(0).path("alt").asText()).isEqualTo("Shop logo");
        JsonNode missing = call("screen_query", "{\"selector\":\".no-such-thing\"}");
        assertThat(missing.path("count").asInt()).isZero();
        assertThat(missing.path("replayCheck").asText()).isEqualTo("count:none");
        assertThatThrownBy(() -> call("screen_query", "{\"selector\":\" \"}")).hasMessageContaining("selector is needed");
    }

    @Test
    void nativeScreensCountControlsButCannotBeReadBySelector() throws Exception {
        NativeSurface.Bridge bridge = new NativeSurface.Bridge() {
            @Override public void openUrl(String url) { }
            @Override public void launchApp(String appId) { }
            @Override public List<Surface.Element> elements() {
                return List.of(new Surface.Element("button", "Place order", "", 0, 1, 1, 1, 1), new Surface.Element("button", "Place order", "", 0, 1, 1, 1, 1));
            }
            @Override public void tap(int x, int y) { }
            @Override public void typeText(String text) { }
            @Override public void swipe(int a, int b, int c, int d) { }
            @Override public void back() { }
        };
        Map<String, Tool> nativeTools = new HashMap<>();
        new ScreenDriver(new NativeSurface(bridge, null, null)).tools().forEach(t -> nativeTools.put(t.name(), t));

        assertThat(nativeTools.get("screen_count").handler().call(Json.parse("{\"role\":\"button\"}")).path("count").asInt()).isEqualTo(2);
        assertThatThrownBy(() -> nativeTools.get("screen_query").handler().call(Json.parse("{\"selector\":\"p\"}")))
                .hasMessageContaining("cannot be read by selector").hasMessageContaining("screen_count");
    }
}

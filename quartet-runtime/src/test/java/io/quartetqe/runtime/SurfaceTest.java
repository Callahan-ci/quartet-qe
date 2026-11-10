package io.quartetqe.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SurfaceTest {
    private static final String ANDROID_XML = """
        noise<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
        <node class="android.widget.FrameLayout" text="" content-desc="" clickable="false" bounds="[0,0][1080,2400]">
          <node class="android.widget.Button" text="Place order" content-desc="" clickable="true" bounds="[100,200][300,300]"/>
          <node class="android.widget.EditText" text="" content-desc="Customer" clickable="true" bounds="[100,400][500,500]"/>
          <node class="android.widget.TextView" text="Total 19.99" content-desc="" clickable="false" bounds="[0,600][400,650]"/>
          <node class="android.widget.Button" text="Place order" content-desc="" clickable="true" bounds="[100,700][300,800]"/>
        </node></hierarchy>UI hierchary dumped to: /dev/tty""";

    private Map<String, Tool> tools(Surface surface) {
        Map<String, Tool> tools = new HashMap<>();
        new ScreenDriver(surface).tools().forEach(tool -> tools.put(tool.name(), tool));
        return tools;
    }

    private static class FakeBridge implements NativeSurface.Bridge {
        final List<String> calls = new ArrayList<>();
        List<Surface.Element> elements = List.of();

        @Override public void openUrl(String url) { calls.add("open " + url); }
        @Override public void launchApp(String appId) { calls.add("launch " + appId); }
        @Override public List<Surface.Element> elements() { return elements; }
        @Override public void tap(int x, int y) { calls.add("tap " + x + "," + y); }
        @Override public void typeText(String text) { calls.add("type " + text); }
        @Override public void swipe(int a, int b, int c, int d) { calls.add("swipe"); }
        @Override public void back() { calls.add("back"); }
    }

    @Test
    void browserAccessibilityTreeBecomesTheScreenModel() {
        Surface.Screen screen = BrowserSurface.parse("""
            - banner:
              - link "Install →":
                - /url: "#quickstart"
              - link "Install →":
                - /url: "#other"
            - heading "Six stages" [level=2]
            - 'heading "Why: this"'
            - paragraph: Plain words
            - text: Your order
            - strong: ORD-1
            - button "Buy" [disabled]
            """);
        assertThat(screen.find("link", "Install →", 1).value()).isEqualTo("#other");
        assertThat(screen.find("heading", "Why: this", 0)).isNotNull();
        assertThat(screen.find("button", "Buy", 0)).isNotNull();
        assertThat(screen.text()).contains("Your order ORD-1");
    }

    @Test
    void androidHierarchyUsesTheSameModel() throws Exception {
        List<String> commands = new ArrayList<>();
        AndroidBridge bridge = new AndroidBridge("emulator-5554", command -> {
            commands.add(String.join(" ", command));
            return command.contains("uiautomator") ? ANDROID_XML : "";
        });
        NativeSurface surface = new NativeSurface(bridge, "https://www.example.com", null);

        Surface.Screen screen = surface.observe();
        surface.tap(screen.find("button", "Place order", 1));
        surface.type(screen.find("textbox", "Customer", 0), "C 7; rm");
        surface.open("/docs");

        assertThat(screen.text()).contains("Total 19.99");
        assertThat(commands).anyMatch(c -> c.endsWith("-s emulator-5554 shell input tap 200 750"));
        assertThat(commands).anyMatch(c -> c.endsWith("shell input text C%s7\\;%srm"));
        assertThat(commands).anyMatch(c -> c.endsWith("shell am force-stop com.android.chrome"));
        assertThat(commands).anyMatch(c -> c.contains("android.intent.action.VIEW -p com.android.chrome -d https://www.example.com/docs"));
    }

    @Test
    void iosRunnerProtocolUsesTheSameModel() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> posts = new ArrayList<>();
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (exchange.getRequestMethod().equals("POST")) posts.add(exchange.getRequestURI().getPath() + " " + body);
            byte[] reply = "{\"elements\":[{\"role\":\"button\",\"name\":\"Pay\",\"x\":10,\"y\":20,\"width\":100,\"height\":40}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            NativeSurface surface = new NativeSurface(new IosBridge("http://127.0.0.1:" + server.getAddress().getPort()), null, "com.shop");
            surface.open("/");
            surface.tap(surface.observe().find("button", "Pay", 0));
            assertThat(posts).containsExactly("/launch {\"bundleId\":\"com.shop\"}", "/tap {\"x\":60,\"y\":40}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chromeWebLinksAreLinksAndNativeViewsAreButtons() throws Exception {
        List<Surface.Element> elements = AndroidBridge.parse("""
            <hierarchy><node class="android.webkit.WebView" text="" content-desc="" clickable="false" bounds="[0,0][1,1]">
              <node class="android.view.View" text="" content-desc="Install" clickable="true" bounds="[0,0][9,9]"/>
            </node><node class="android.view.View" text="" content-desc="Menu" clickable="true" bounds="[0,0][9,9]"/></hierarchy>""");
        assertThat(elements).extracting(Surface.Element::role, Surface.Element::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("link", "Install"), org.assertj.core.groups.Tuple.tuple("button", "Menu"));
    }

    @Test
    void offScreenElementsAreScrolledIntoViewBeforeTapping() throws Exception {
        FakeBridge bridge = new FakeBridge() {
            @Override
            public void swipe(int a, int b, int c, int d) {
                super.swipe(a, b, c, d);
                elements = List.of(new Surface.Element("link", "Docs", "", 0, 10, 20, 100, 40));
            }
        };
        bridge.elements = List.of(new Surface.Element("link", "Docs", "", 0, 0, 0, 0, 0));
        NativeSurface surface = new NativeSurface(bridge, null, null);
        surface.tap(surface.observe().find("link", "Docs", 0));
        assertThat(bridge.calls).containsExactly("swipe", "tap 60,40");
    }

    @Test
    void nativeAppsLaunchByIdentifierAndWebsitesByUrl() throws Exception {
        FakeBridge bridge = new FakeBridge();
        new NativeSurface(bridge, null, "com.shop").open("/");
        assertThat(bridge.calls).containsExactly("launch com.shop");
    }

    @Test
    void aRenamedControlIsNeverGuessed() {
        FakeBridge bridge = new FakeBridge();
        bridge.elements = List.of(new Surface.Element("button", "Place your order", "", 0, 0, 0, 10, 10));
        Map<String, Tool> tools = tools(new NativeSurface(bridge, null, null));
        assertThatThrownBy(() -> tools.get("screen_tap").handler().call(Json.obj().put("role", "button").put("name", "Place order")))
                .hasMessageContaining("No button named");
        assertThat(bridge.calls).isEmpty();
    }

    @Test
    void textIsReadFromAnySurfaceAndAbsenceIsReported() throws Exception {
        FakeBridge bridge = new FakeBridge();
        bridge.elements = List.of(new Surface.Element("text", "Order ORD-77 confirmed", "", 0, 0, 0, 10, 10));
        Map<String, Tool> tools = tools(new NativeSurface(bridge, null, null));
        assertThat(tools.get("screen_find_text").handler().call(Json.obj().put("pattern", "ORD-[0-9]+")).path("text").asText()).isEqualTo("ORD-77");
        assertThat(tools.get("screen_find_text").handler().call(Json.obj().put("pattern", "NOPE").put("timeoutMs", 10)).path("found").asBoolean()).isFalse();
    }
}

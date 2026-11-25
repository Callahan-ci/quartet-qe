package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The agent's one set of screen tools, identical on web, mobile web and native. */
final class ScreenDriver implements Driver {
    private static final long WEB_WAIT_MS = 8000;
    // A phone or emulator draws a new screen more slowly than a browser, and was seen missing the 8 second mark under load.
    private static final long NATIVE_WAIT_MS = 20_000;
    private static final String CONTROL = """
        {"type":"object","required":["role","name"],"properties":{
          "role":{"type":"string"},"name":{"type":"string"},
          "occurrence":{"type":"integer","description":"Zero-based position among controls with the same role and name"}}}""";

    private final Surface surface;

    private long waitMs() {
        String override = System.getenv("QUARTET_WAIT_MS");
        if (override != null && override.matches("\\d{3,6}")) return Long.parseLong(override);
        return surface instanceof NativeSurface ? NATIVE_WAIT_MS : WEB_WAIT_MS;
    }

    ScreenDriver(Environment environment, String target, String requestedDevice, boolean headless) {
        JsonNode config = environment.target(target);
        String device = requestedDevice != null && config.path("devices").has(requestedDevice)
                ? requestedDevice : config.path("devices").fieldNames().next();
        JsonNode settings = config.path("devices").path(device);
        String baseUrl = config.path("baseUrl").asText(null);
        this.surface = switch (settings.path("kind").asText("web")) {
            case "web" -> new BrowserSurface(baseUrl, settings, headless, environment::allowed);
            case "android" -> new NativeSurface(new AndroidBridge(settings.path("serial").asText(null)), baseUrl, config.path("app").path("android").asText(null));
            case "ios" -> new NativeSurface(new IosBridge(settings.path("runnerUrl").asText("http://127.0.0.1:8123")), baseUrl, config.path("app").path("ios").asText(null));
            default -> throw new IllegalArgumentException("Unknown device kind for " + device);
        };
    }

    ScreenDriver(Surface surface) {
        this.surface = surface;
    }

    /** Page requests to the app carry the test's trace. Native apps make their own requests, so they are left alone. */
    void traceWith(java.util.function.Supplier<String> header) {
        if (surface instanceof BrowserSurface browser) browser.traceWith(header);
    }

    /** Escalating pause between looks at the screen while it settles. */
    private static final long[] RETRY_DELAYS_MS = {100, 300, 600, 1_000, 3_000};

    private static void pause(int attempt) throws InterruptedException {
        int index = Math.min(attempt, RETRY_DELAYS_MS.length - 1);
        Thread.sleep(RETRY_DELAYS_MS[index]);
    }

    /** Waits for an exact role and name. Throws when it never appears, which starts a hand-off in replay. */
    @Override
    public byte[] screenshot() {
        return surface.screenshot();
    }

    private Surface.Element await(JsonNode args) throws Exception {
        String role = args.path("role").asText();
        String name = args.path("name").asText();
        int occurrence = args.path("occurrence").asInt(0);
        long deadline = System.currentTimeMillis() + waitMs();
        int attempt = 0;
        while (true) {
            Surface.Element element = surface.observe().find(role, name, occurrence);
            if (element != null) return element;
            if (System.currentTimeMillis() >= deadline) throw new NoSuchElementException("No " + role + " named \"" + name + "\" on screen.");
            pause(attempt++);
        }
    }

    private static ObjectNode ok() {
        return Driver.check(Json.obj().put("done", true), "ok");
    }

    @Override
    public List<Tool> tools() {
        return List.of(
                Tool.of("screen_open", "Open a path, for example /. On a native app, / launches the app.",
                        "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}", args -> {
                    surface.open(args.path("path").asText());
                    return ok();
                }),
                Tool.of("screen_observe", "List what is on screen, by role and name, with all visible text.", "{\"type\":\"object\",\"properties\":{}}", args -> {
                    Surface.Screen screen = surface.observe();
                    ObjectNode out = Json.obj().put("text", screen.text());
                    var elements = out.putArray("elements");
                    screen.elements().stream().filter(e -> !e.name().isEmpty()).limit(250).forEach(e -> elements.add(e.describe()));
                    return out;
                }),
                Tool.of("screen_tap", "Tap a control. Waits for it to appear.", CONTROL, args -> {
                    surface.tap(await(args));
                    return ok();
                }),
                Tool.of("screen_type", "Replace the text in a field.", """
                    {"type":"object","required":["role","name","text"],"properties":{"role":{"type":"string"},"name":{"type":"string"},"text":{"type":"string"},"occurrence":{"type":"integer"}}}""", args -> {
                    surface.type(await(args), args.path("text").asText());
                    return ok();
                }),
                Tool.of("screen_choose", "Choose an option in a web dropdown.", """
                    {"type":"object","required":["role","name","value"],"properties":{"role":{"type":"string"},"name":{"type":"string"},"value":{"type":"string"},"occurrence":{"type":"integer"}}}""", args -> {
                    surface.choose(await(args), args.path("value").asText());
                    return ok();
                }),
                Tool.of("screen_scroll", "Scroll the screen.", "{\"type\":\"object\",\"required\":[\"direction\"],\"properties\":{\"direction\":{\"enum\":[\"up\",\"down\"]}}}", args -> {
                    surface.scroll(args.path("direction").asText());
                    return ok();
                }),
                Tool.of("screen_back", "Go back.", "{\"type\":\"object\",\"properties\":{}}", args -> {
                    surface.back();
                    return ok();
                }),
                Tool.of("screen_read", "Read one control: its name, value and link address. Reports found=false if it never appears.", CONTROL, this::read),
                Tool.of("screen_find_text", "Wait for visible text matching a regular expression and return it as text. If the pattern has a capture group, text is just that group, so you can read an unlabelled value that sits between known words, such as a calculator result shown before the % key. The whole match is returned as matched.", """
                    {"type":"object","required":["pattern"],"properties":{"pattern":{"type":"string"},"timeoutMs":{"type":"integer"}}}""", this::findText),
                Tool.of("screen_count", "Count controls on the screen by role and/or part of their name, for example how many links or how many buttons named Add to basket. Use this instead of counting by eye.", """
                    {"type":"object","properties":{"role":{"type":"string"},"nameContains":{"type":"string","description":"Case-insensitive part of the name"}}}""", this::count),
                Tool.of("screen_query", "Read elements matched by a CSS selector on a web page: how many match (count), their text (texts), and chosen attributes. Use it for what the accessibility tree does not show, such as a rating kept in a CSS class (attributes: [\"class\"], then read classes), an image's alt text or a link's href. Read-only. Web only.", """
                    {"type":"object","required":["selector"],"properties":{"selector":{"type":"string","description":"A CSS selector, for example p.star-rating"},"attributes":{"type":"array","items":{"type":"string"}},"limit":{"type":"integer","description":"How many matches to read in detail. Default 5, up to 50."}}}""", this::query),
                Tool.of("screen_layout", "Report layout facts: viewportWidth, contentWidth and overflowPx. Empty on surfaces that cannot report them.", "{\"type\":\"object\",\"properties\":{}}", args -> {
                    ObjectNode out = Json.obj();
                    surface.layout().forEach(out::put);
                    return Driver.check(out, "ok");
                }));
    }

    private JsonNode count(JsonNode args) throws Exception {
        String role = args.path("role").asText("");
        String part = args.path("nameContains").asText("").toLowerCase();
        if (role.isBlank() && part.isBlank()) throw new IllegalArgumentException("Give role, nameContains or both.");
        long count = surface.observe().elements().stream()
                .filter(e -> role.isBlank() || e.role().equals(role))
                .filter(e -> part.isBlank() || e.name().toLowerCase().contains(part)).count();
        return Driver.check(Json.obj().put("count", count), count == 0 ? "count:none" : "count:some");
    }

    private JsonNode query(JsonNode args) throws Exception {
        String selector = args.path("selector").asText("").strip();
        if (selector.isEmpty()) throw new IllegalArgumentException("selector is needed.");
        List<String> attributes = new java.util.ArrayList<>();
        args.path("attributes").forEach(a -> attributes.add(a.asText()));
        int limit = Math.max(1, Math.min(50, args.path("limit").asInt(5)));
        Surface.Query found = surface.query(selector, attributes, limit);
        ObjectNode out = Json.obj().put("count", found.count());
        var texts = out.putArray("texts");
        found.texts().forEach(texts::add);
        var list = out.putArray("attributes");
        for (var values : found.attributes()) {
            ObjectNode item = list.addObject();
            values.forEach(item::put);
            // A CSS class list is also given as separate words, so "star-rating Three" can be read as Three.
            if (values.containsKey("class")) {
                var classes = item.putArray("classes");
                for (String word : values.get("class").strip().split("\\s+")) classes.add(word);
            }
        }
        return Driver.check(out, found.count() == 0 ? "count:none" : "count:some");
    }

    private JsonNode read(JsonNode args) throws Exception {
        Surface.Element element;
        try {
            element = await(args);
        } catch (NoSuchElementException e) {
            return Driver.check(Json.obj().put("found", false), "found:false");
        }
        return Driver.check(Json.obj().put("found", true).put("role", element.role()).put("name", element.name()).put("value", element.value()), "found:true");
    }

    private JsonNode findText(JsonNode args) throws Exception {
        Pattern pattern = Pattern.compile(args.path("pattern").asText());
        long deadline = System.currentTimeMillis() + args.path("timeoutMs").asLong(waitMs());
        int attempt = 0;
        while (true) {
            Matcher matcher = pattern.matcher(surface.observe().text());
            if (matcher.find()) {
                // With a capture group the text is just the group, so a value between two known words can be read on its own.
                String text = matcher.groupCount() >= 1 && matcher.group(1) != null ? matcher.group(1) : matcher.group();
                return Driver.check(Json.obj().put("found", true).put("text", text).put("matched", matcher.group()), "found:true");
            }
            if (System.currentTimeMillis() >= deadline) return Driver.check(Json.obj().put("found", false), "found:false");
            pause(attempt++);
        }
    }

    @Override
    public void close() throws Exception {
        surface.close();
    }

    Surface surface() {
        return surface;
    }
}

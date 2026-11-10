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
    private static final long WAIT_MS = 8000;
    private static final String CONTROL = """
        {"type":"object","required":["role","name"],"properties":{
          "role":{"type":"string"},"name":{"type":"string"},
          "occurrence":{"type":"integer","description":"Zero-based position among controls with the same role and name"}}}""";

    private final Surface surface;

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

    /** Waits for an exact role and name. Throws when it never appears, which starts a hand-off in replay. */
    private Surface.Element await(JsonNode args) throws Exception {
        String role = args.path("role").asText();
        String name = args.path("name").asText();
        int occurrence = args.path("occurrence").asInt(0);
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (true) {
            Surface.Element element = surface.observe().find(role, name, occurrence);
            if (element != null) return element;
            if (System.currentTimeMillis() >= deadline) throw new NoSuchElementException("No " + role + " named \"" + name + "\" on screen.");
            Thread.sleep(150);
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
                Tool.of("screen_find_text", "Wait for visible text matching a regular expression and return the matched text.", """
                    {"type":"object","required":["pattern"],"properties":{"pattern":{"type":"string"},"timeoutMs":{"type":"integer"}}}""", this::findText),
                Tool.of("screen_layout", "Report layout facts: viewportWidth, contentWidth and overflowPx. Empty on surfaces that cannot report them.", "{\"type\":\"object\",\"properties\":{}}", args -> {
                    ObjectNode out = Json.obj();
                    surface.layout().forEach(out::put);
                    return Driver.check(out, "ok");
                }));
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
        long deadline = System.currentTimeMillis() + args.path("timeoutMs").asLong(WAIT_MS);
        while (true) {
            Matcher matcher = pattern.matcher(surface.observe().text());
            if (matcher.find()) return Driver.check(Json.obj().put("found", true).put("text", matcher.group()), "found:true");
            if (System.currentTimeMillis() >= deadline) return Driver.check(Json.obj().put("found", false), "found:false");
            Thread.sleep(150);
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

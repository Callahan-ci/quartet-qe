package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Desktop and phone browsers. Reads the browser's own accessibility tree, the meaning a user gets. */
final class BrowserSurface implements Surface {
    private record Preset(int width, int height, double scale, boolean mobile, String userAgent) {}

    private static final Map<String, Preset> PRESETS = Map.of(
            "desktop", new Preset(1440, 900, 1, false, null),
            "iphone", new Preset(393, 659, 3, true, "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"),
            "android", new Preset(412, 839, 2.625, true, "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"));
    private static final Pattern LINE = Pattern.compile("^([a-zA-Z]+)(?: \"((?:[^\"\\\\]|\\\\.)*)\")?((?: \\[[^\\]]*])*)(?::\\s*(.*))?$");
    private static final double TIMEOUT_MS = 8000;

    private final String baseUrl;
    private final String engine;
    private final Preset preset;
    private final boolean headless;
    private final Predicate<String> allowedHost;
    private Playwright playwright;
    private Page page;

    BrowserSurface(String baseUrl, JsonNode device, boolean headless, Predicate<String> allowedHost) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.engine = device.path("engine").asText("chromium");
        this.preset = PRESETS.get(device.path("preset").asText("desktop"));
        if (preset == null) throw new IllegalArgumentException("Unknown device preset. Use one of " + PRESETS.keySet());
        this.headless = headless;
        this.allowedHost = allowedHost;
    }

    private synchronized Page page() {
        if (page != null) return page;
        playwright = Playwright.create();
        BrowserType type = engine.equals("webkit") ? playwright.webkit() : playwright.chromium();
        Browser browser = type.launch(new BrowserType.LaunchOptions().setHeadless(headless));
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setViewportSize(preset.width(), preset.height())
                .setDeviceScaleFactor(preset.scale())
                .setIsMobile(preset.mobile())
                .setHasTouch(preset.mobile());
        if (preset.userAgent() != null) options.setUserAgent(preset.userAgent());
        page = browser.newContext(options).newPage();
        // The agent may read other sites' links, but it can only travel to allow-listed hosts.
        page.route("**/*", route -> {
            String url = route.request().url();
            boolean leaving = route.request().isNavigationRequest() && url.startsWith("http") && !allowedHost.test(URI.create(url).getHost());
            if (leaving) route.abort("blockedbyclient");
            else route.resume();
        });
        return page;
    }

    @Override
    public void open(String path) {
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("path must start with a single /");
        page().navigate(baseUrl + path);
    }

    @Override
    public Screen observe() {
        return parse(page().locator("body").ariaSnapshot());
    }

    /** Turns Playwright's accessibility snapshot into the shared screen model. */
    static Screen parse(String snapshot) {
        List<Element> elements = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (String rawLine : snapshot.split("\n")) {
            String line = rawLine.strip();
            if (!line.startsWith("- ")) continue;
            line = line.substring(2);
            if (line.startsWith("'") && line.lastIndexOf('\'') > 0) {
                int end = line.lastIndexOf('\'');
                line = line.substring(1, end).replace("''", "'") + line.substring(end + 1);
            }
            if (line.startsWith("/url:")) {
                if (!elements.isEmpty()) {
                    Element last = elements.remove(elements.size() - 1);
                    elements.add(new Element(last.role(), last.name(), unquote(line.substring(5).strip()), 0, 0, 0, 0, 0));
                }
                continue;
            }
            if (line.startsWith("/")) continue;
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) continue;
            String role = matcher.group(1);
            String name = matcher.group(2) == null ? "" : matcher.group(2).replace("\\\"", "\"");
            String inline = matcher.group(4) == null ? "" : unquote(matcher.group(4).strip());
            if (!name.isEmpty()) text.append(name).append(' ');
            if (!inline.isEmpty()) text.append(inline).append(' ');
            if (!role.equals("text")) elements.add(new Element(role, name.isEmpty() ? inline : name, name.isEmpty() ? "" : inline, 0, 0, 0, 0, 0));
        }
        return new Screen(Surface.numbered(elements), text.toString().replaceAll("\\s+", " ").trim());
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }

    private Locator locate(Element element) {
        return page().getByRole(AriaRole.valueOf(element.role().toUpperCase()), new Page.GetByRoleOptions().setName(element.name()).setExact(true)).nth(element.occurrence());
    }

    @Override
    public void tap(Element element) {
        Locator locator = locate(element);
        if (preset.mobile()) locator.tap(new Locator.TapOptions().setTimeout(TIMEOUT_MS));
        else locator.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
    }

    @Override
    public void type(Element element, String text) {
        locate(element).fill(text, new Locator.FillOptions().setTimeout(TIMEOUT_MS));
    }

    @Override
    public void choose(Element element, String value) {
        locate(element).selectOption(value, new Locator.SelectOptionOptions().setTimeout(TIMEOUT_MS));
    }

    @Override
    public void scroll(String direction) {
        page().mouse().wheel(0, direction.equals("up") ? -600 : 600);
    }

    @Override
    public void back() {
        page().goBack();
    }

    @Override
    public Map<String, Integer> layout() {
        int viewport = ((Number) page().evaluate("() => window.innerWidth")).intValue();
        int content = ((Number) page().evaluate("() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth)")).intValue();
        return Map.of("viewportWidth", viewport, "contentWidth", content, "overflowPx", Math.max(0, content - viewport));
    }

    @Override
    public void close() {
        if (playwright != null) playwright.close();
    }
}

package io.quartetqe.runtime;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringJoiner;

/** Native apps and on-device browsers, reached through a small platform bridge. */
final class NativeSurface implements Surface {
    /** The few operations a platform has to provide. */
    interface Bridge extends AutoCloseable {
        void openUrl(String url) throws Exception;

        void launchApp(String appId) throws Exception;

        List<Element> elements() throws Exception;

        void tap(int x, int y) throws Exception;

        void typeText(String text) throws Exception;

        void swipe(int fromX, int fromY, int toX, int toY) throws Exception;

        void back() throws Exception;

        @Override
        default void close() throws Exception {}
    }

    private static final int MAX_SCROLLS = 15;

    private final Bridge bridge;
    private final String baseUrl;
    private final String appId;

    NativeSurface(Bridge bridge, String baseUrl, String appId) {
        this.bridge = bridge;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.appId = appId;
    }

    @Override
    public void open(String path) throws Exception {
        if (appId != null && !appId.isBlank() && (path.isEmpty() || path.equals("/"))) bridge.launchApp(appId);
        else if (baseUrl.isEmpty()) throw new IllegalArgumentException("This target has no baseUrl to open a path on.");
        else bridge.openUrl(baseUrl + path);
    }

    @Override
    public Screen observe() throws Exception {
        List<Element> elements = Surface.numbered(bridge.elements());
        StringJoiner text = new StringJoiner(" ");
        for (Element element : elements) {
            if (!element.name().isEmpty()) text.add(element.name());
            if (element.value() != null && !element.value().isEmpty()) text.add(element.value());
        }
        return new Screen(elements, text.toString());
    }

    @Override
    public void tap(Element element) throws Exception {
        Element target = bringIntoView(element);
        bridge.tap(target.x() + target.width() / 2, target.y() + target.height() / 2);
    }

    /** Devices report off-screen elements with no size, so scroll until the same element has one. */
    private Element bringIntoView(Element element) throws Exception {
        Element current = element;
        for (int attempt = 0; current.width() == 0 && attempt < MAX_SCROLLS; attempt++) {
            scroll("down");
            Element found = observe().find(element.role(), element.name(), element.occurrence());
            if (found != null) current = found;
        }
        if (current.width() == 0) throw new NoSuchElementException("Could not scroll to " + element.describe());
        return current;
    }

    @Override
    public void type(Element element, String text) throws Exception {
        tap(element);
        bridge.typeText(text);
    }

    @Override
    public void choose(Element element, String value) {
        throw new UnsupportedOperationException("Native pickers have no single choose action. Tap the control, then tap the option.");
    }

    @Override
    public void scroll(String direction) throws Exception {
        List<Element> elements = bridge.elements();
        int width = elements.stream().mapToInt(e -> e.x() + e.width()).max().orElse(1000);
        int height = elements.stream().mapToInt(e -> e.y() + e.height()).max().orElse(2000);
        int top = height / 4;
        int bottom = height * 3 / 4;
        if (direction.equals("up")) bridge.swipe(width / 2, top, width / 2, bottom);
        else bridge.swipe(width / 2, bottom, width / 2, top);
    }

    @Override
    public void back() throws Exception {
        bridge.back();
    }

    @Override
    public void close() throws Exception {
        bridge.close();
    }
}

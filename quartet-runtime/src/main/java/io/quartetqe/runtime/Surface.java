package io.quartetqe.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anything with a screen: a browser, a phone browser or a native app.
 *
 * Every surface reports the same model of roles and names, so one plain-English test and one set of
 * agent tools cover them all. Recordings store meaning (button "Place order"), never selectors.
 */
public interface Surface extends AutoCloseable {
    record Element(String role, String name, String value, int occurrence, int x, int y, int width, int height) {
        String describe() {
            return role + (name.isEmpty() ? "" : " \"" + name + "\"") + (occurrence > 0 ? " #" + occurrence : "")
                    + (value == null || value.isEmpty() ? "" : " = " + value);
        }
    }

    record Screen(List<Element> elements, String text) {
        /** Exact match only. A control that was renamed is a hand-off, never a tap on the closest match. */
        Element find(String role, String name, int occurrence) {
            for (Element element : elements) {
                if (element.role().equals(role) && element.name().equals(name) && element.occurrence() == occurrence) return element;
            }
            return null;
        }
    }

    /** Numbers repeated controls so the third "Read more" stays the third. */
    static List<Element> numbered(List<Element> raw) {
        Map<String, Integer> seen = new HashMap<>();
        List<Element> out = new ArrayList<>();
        for (Element e : raw) {
            int occurrence = seen.merge(e.role() + "|" + e.name(), 1, Integer::sum) - 1;
            out.add(new Element(e.role(), e.name(), e.value(), occurrence, e.x(), e.y(), e.width(), e.height()));
        }
        return out;
    }

    /** Opens a path on a website, or launches the app when the path is empty and an app is configured. */
    void open(String path) throws Exception;

    Screen observe() throws Exception;

    void tap(Element element) throws Exception;

    void type(Element element, String text) throws Exception;

    void choose(Element element, String value) throws Exception;

    void scroll(String direction) throws Exception;

    void back() throws Exception;

    /** Layout facts such as horizontal overflow. Empty when a surface cannot report them. */
    default Map<String, Integer> layout() throws Exception {
        return Map.of();
    }

    @Override
    default void close() throws Exception {}
}

package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.HttpJson;
import io.quartetqe.core.Json;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * iOS through our own runner in native/ios, which speaks a small JSON protocol.
 * Apple only allows automation through XCTest, so this needs Xcode.
 */
final class IosBridge implements NativeSurface.Bridge {
    private final String runnerUrl;
    private final HttpJson http = new HttpJson();

    IosBridge(String runnerUrl) {
        this.runnerUrl = runnerUrl.replaceAll("/$", "");
    }

    private HttpJson.Resp post(String path, Object body) {
        HttpJson.Resp response = http.send("POST", runnerUrl + path, Map.of("content-type", "application/json"), Json.str(body), Duration.ofSeconds(30));
        if (!response.ok()) throw new IllegalStateException("iOS runner " + path + " returned " + response.status() + ": " + response.body());
        return response;
    }

    @Override
    public void openUrl(String url) {
        post("/open", Json.obj().put("url", url));
    }

    @Override
    public void launchApp(String appId) {
        post("/launch", Json.obj().put("bundleId", appId));
    }

    @Override
    public List<Surface.Element> elements() {
        HttpJson.Resp response = http.send("GET", runnerUrl + "/screen", Map.of(), null, Duration.ofSeconds(30));
        if (!response.ok()) throw new IllegalStateException("iOS runner /screen returned " + response.status());
        List<Surface.Element> out = new ArrayList<>();
        for (JsonNode e : response.json().path("elements")) {
            out.add(new Surface.Element(e.path("role").asText(), e.path("name").asText(), e.path("value").asText(""), 0,
                    e.path("x").asInt(), e.path("y").asInt(), e.path("width").asInt(), e.path("height").asInt()));
        }
        return out;
    }

    @Override
    public void tap(int x, int y) {
        post("/tap", Json.obj().put("x", x).put("y", y));
    }

    @Override
    public void typeText(String text) {
        post("/type", Json.obj().put("text", text));
    }

    @Override
    public void swipe(int fromX, int fromY, int toX, int toY) {
        post("/swipe", Json.obj().put("fromX", fromX).put("fromY", fromY).put("toX", toX).put("toY", toY));
    }

    @Override
    public void back() {
        swipe(0, 300, 300, 300);
    }
}

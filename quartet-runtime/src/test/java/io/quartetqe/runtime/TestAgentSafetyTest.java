package io.quartetqe.runtime;

import static io.quartetqe.runtime.Act.on;
import static io.quartetqe.runtime.Act.returns;
import static io.quartetqe.runtime.Act.why;
import static io.quartetqe.runtime.Act.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.quartetqe.core.AgentLoop;
import io.quartetqe.core.Json;
import io.quartetqe.core.Llm;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The promises the framework makes about AI behaviour, tested through its public act and verify calls. */
class TestAgentSafetyTest {
    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicInteger posts = new AtomicInteger();
    private volatile String status = "RESERVED";
    private volatile int getStatus = 200;
    private TestAgent agent;
    private ScriptLlm llm;
    private ActRecorder recorder;

    /** Returns tool calls in order and captures every prompt it receives. */
    private static final class ScriptLlm implements Llm.Client {
        final Deque<Llm.ToolCall> calls = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        final List<String> toolResults = new ArrayList<>();

        void add(String name, JsonNode args) {
            calls.add(new Llm.ToolCall("c" + calls.size(), name, args));
        }

        @Override
        public Llm.Response complete(Llm.Request request) {
            prompts.add(request.messages().get(0).text());
            request.messages().forEach(m -> m.toolResults().forEach(r -> toolResults.add(r.content())));
            if (calls.isEmpty()) throw new Llm.LlmException("script exhausted");
            return new Llm.Response("", List.of(calls.poll()), null, "tool_use", 1, 1);
        }

        @Override
        public String model() {
            return "test";
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean post = exchange.getRequestMethod().equals("POST");
            if (post) posts.incrementAndGet();
            byte[] body = ("{\"orderId\":\"ORD-12345678\",\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(post ? 201 : getStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            byte[] body = "<html><body><h1>Shop</h1><button>Place order</button></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Path context = dir.resolve("context/environments");
        Files.createDirectories(context);
        Path envFile = context.resolve("t.yaml");
        Files.writeString(envFile, """
            name: t
            guard: {allowHosts: [127.0.0.1]}
            targets:
              api: {type: api, description: d, baseUrl: "http://127.0.0.1:%d"}
              site:
                type: screen
                description: d
                baseUrl: "http://127.0.0.1:%d"
                devices:
                  desktop: {kind: web, engine: chromium}
            """.formatted(server.getAddress().getPort(), server.getAddress().getPort()));
        llm = new ScriptLlm();
        agent = agent(envFile, TestAgent.Mode.AUTO);
    }

    private TestAgent agent(Path envFile, TestAgent.Mode mode) {
        recorder = new ActRecorder(dir.resolve("cache"), dir.resolve("report.jsonl"));
        return new TestAgent(Environment.load(envFile, Map.of()), () -> llm, mode, recorder, "T.m", "S-1", null, true);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private JsonNode post() {
        return Json.obj().put("method", "POST").put("path", "/orders");
    }

    private JsonNode get() {
        return Json.obj().put("method", "GET").put("path", "/orders/1");
    }

    private JsonNode finish(JsonNode data) {
        var finish = Json.obj().put("ok", true).put("summary", "done");
        finish.set("data", data);
        return finish;
    }

    private ActResult place() {
        return agent.act("Place an order", why("Business reason for the order"), on("api"), returns("status"));
    }

    @Test
    void aFailingTestCanShowTheScreenItReached() {
        llm.add("screen_open", Json.obj().put("path", "/"));
        llm.add(AgentLoop.FINISH, finish(Json.obj()));
        agent.act("Open the site home page", why("arrivals"), on("site"));

        Map<String, byte[]> shots = agent.failureScreenshots();
        assertThat(shots).containsKey("site");
        assertThat(shots.get("site")).startsWith(new byte[] {-119, 80, 78, 71, 13});
    }

    @Test
    void aTestThatNeverOpenedAScreenAsksForNoScreenshotAndLaunchesNoBrowser() {
        llm.add("http_request", get());
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        agent.act("Read the order", why("status is visible"), on("api"), returns("status"));

        assertThat(agent.failureScreenshots()).isEmpty();
    }

    @Test
    void aValueNoToolReturnedIsRejectedAndTheModelMustCorrectIt() {
        llm.add("http_request", post());
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "CONFIRMED")));
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));

        assertThat(place().get("status")).isEqualTo("RESERVED");
        assertThat(llm.toolResults).anyMatch(r -> r.contains("not observed in any tool result"));
    }

    @Test
    void recordingIsSavedOnlyAfterTheTestPasses() throws IOException {
        llm.add("http_request", post());
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        place();
        agent.testFailed();
        assertThat(Files.exists(dir.resolve("cache"))).isFalse();
    }

    @Test
    void replayCallsNoModelAndReadsFreshValuesSoRegressionsAreVisible() {
        llm.add("http_request", post());
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        place();
        agent.testPassed();
        int promptsAfterRecording = llm.prompts.size();

        status = "BROKEN";
        ActResult replayed = place();

        assertThat(replayed.source()).isEqualTo(ActResult.Source.REPLAYED);
        assertThat(llm.prompts).hasSize(promptsAfterRecording);
        assertThat(replayed.get("status")).isEqualTo("BROKEN");
    }

    @Test
    void handOffDoesNotRepeatStepsThatAlreadyRan() {
        llm.add("http_request", post());
        llm.add("http_request", get());
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        place();
        agent.testPassed();
        assertThat(posts.get()).isEqualTo(1);

        getStatus = 500;
        llm.add("http_request", Json.obj().put("method", "GET").put("path", "/orders/2"));
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        ActResult healed = place();

        assertThat(healed.source()).isEqualTo(ActResult.Source.HANDED_OFF);
        assertThat(posts.get()).as("replay ran the POST once and the model must not repeat it").isEqualTo(2);
        assertThat(llm.prompts.get(llm.prompts.size() - 1)).contains("Replay stopped").contains("Do not repeat them");
    }

    @Test
    void replayOnlyModeNeverFallsBackToTheModel() {
        Path envFile = dir.resolve("context/environments/t.yaml");
        TestAgent strict = agent(envFile, TestAgent.Mode.REPLAY);
        assertThatThrownBy(() -> strict.act("Place an order", why("Business reason for the order"), on("api")))
                .isInstanceOf(AgentActException.class).hasMessageContaining("No recording for");
        assertThat(llm.prompts).isEmpty();
    }

    @Test
    void aRecordingUnderAnOldReplayPolicyIsStaleAndIsReRecorded() throws IOException {
        llm.add("http_request", post());
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        place();
        agent.testPassed();
        Files.walk(dir.resolve("cache")).filter(p -> p.toString().endsWith(".json")).forEach(p -> {
            try {
                Files.writeString(p, Files.readString(p).replaceFirst("\"replayPolicy\" : \"quartet/1\",", "\"replayPolicy\" : \"quartet/0\","));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        TestAgent strict = agent(dir.resolve("context/environments/t.yaml"), TestAgent.Mode.REPLAY);
        assertThatThrownBy(() -> strict.act("Place an order", why("Business reason for the order"), on("api"), returns("status")))
                .isInstanceOf(AgentActException.class).hasMessageContaining("Recording is stale").hasMessageContaining("replay policy quartet/0");

        llm.add("http_request", post());
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        int promptsBefore = llm.prompts.size();
        ActResult live = agent(dir.resolve("context/environments/t.yaml"), TestAgent.Mode.AUTO)
                .act("Place an order", why("Business reason for the order"), on("api"), returns("status"));
        assertThat(live.source()).isNotEqualTo(ActResult.Source.REPLAYED);
        assertThat(llm.prompts.size()).isGreaterThan(promptsBefore);
    }

    @Test
    void evidenceThatWrappedAcrossLinesStillCountsAsGrounded() throws IOException {
        server.createContext("/wrapped", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"note\":\"Order reserved      for customer C-42\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        llm.add("http_request", Json.obj().put("method", "GET").put("path", "/wrapped"));
        llm.add(AgentLoop.FINISH, Json.obj().put("holds", true)
                .set("evidence", Json.arr().add("Order reserved for customer C-42")));

        agent.verify("The order is reserved", why("Customers expect a reserved order"), on("api"));
    }

    @Test
    void verifyFailsWhenQuotedEvidenceWasNeverSeen() {
        llm.add("http_request", get());
        llm.add(AgentLoop.FINISH, Json.obj().put("holds", true).set("evidence", Json.arr().add("Order delivered")));
        llm.add(AgentLoop.FINISH, Json.obj().put("holds", false).set("evidence", Json.arr()));

        assertThatThrownBy(() -> agent.verify("The order was delivered", why("Customers expect delivery updates"), on("api")))
                .isInstanceOf(AssertionError.class);
        assertThat(llm.toolResults).anyMatch(r -> r.contains("Evidence not found"));
    }

    @Test
    void verifyCannotChangeTheSystem() {
        llm.add("http_request", post());
        llm.add(AgentLoop.FINISH, Json.obj().put("holds", false).set("evidence", Json.arr()));

        assertThatThrownBy(() -> agent.verify("Claim", why("Business reason for the claim"), on("api"))).isInstanceOf(AssertionError.class);
        assertThat(posts.get()).isZero();
    }

    @Test
    void everyActMustSayWhyAndWhere() {
        assertThatThrownBy(() -> agent.act("x", on("api"))).hasMessageContaining("why");
        assertThatThrownBy(() -> agent.act("x", why("reason"))).hasMessageContaining("on(");
        assertThatThrownBy(() -> agent.act("x", why("reason"), on("nope"))).hasMessageContaining("Unknown target");
    }

    @Test
    void variablesKeepRecordingsValidAcrossRuns() {
        llm.add("http_request", Json.obj().put("method", "GET").put("path", "/orders/ABC-111"));
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        agent.act("Read order {{id}}", with("id", "ABC-111"), why("Business reason for reading"), on("api"), returns("status"));
        agent.testPassed();
        int before = llm.prompts.size();

        ActResult second = agent.act("Read order {{id}}", with("id", "XYZ-222"), why("Business reason for reading"), on("api"), returns("status"));

        assertThat(second.source()).isEqualTo(ActResult.Source.REPLAYED);
        assertThat(llm.prompts).hasSize(before);
    }

    @Test
    void hostsOutsideTheAllowListAreRefused() throws IOException {
        Path file = dir.resolve("context/environments/bad.yaml");
        Files.writeString(file, """
            name: bad
            guard: {allowHosts: [localhost]}
            targets:
              api: {type: api, baseUrl: "https://payments.example.com"}
            """);
        assertThatThrownBy(() -> Environment.load(file, Map.of())).hasMessageContaining("outside guard.allowHosts");
    }

    @Test
    void productionNeedsAnExplicitOverride() throws IOException {
        Path file = dir.resolve("context/environments/prod.yaml");
        Files.writeString(file, "name: prod\nguard: {production: true, allowHosts: [localhost]}\ntargets: {}\n");
        assertThatThrownBy(() -> Environment.load(file, Map.of())).hasMessageContaining("QUARTET_ALLOW_PRODUCTION");
        assertThat(Environment.load(file, Map.of("QUARTET_ALLOW_PRODUCTION", "true")).name()).isEqualTo("prod");
    }

    @Test
    void generatedEventsAreReproducibleAndHonourOverrides() {
        JsonNode schema = Json.parse("""
            {"type":"object","properties":{"id":{"type":"string","format":"uuid"},"status":{"enum":["A","B"]},"nested":{"type":"object","properties":{"n":{"type":"integer","minimum":5,"maximum":5}}}}}""");
        JsonNode overrides = Json.obj().put("status", "FIXED");
        JsonNode first = KafkaGenerator.generate(schema, overrides, 7);
        assertThat(first).isEqualTo(KafkaGenerator.generate(schema, overrides, 7));
        assertThat(first.path("status").asText()).isEqualTo("FIXED");
        assertThat(first.path("nested").path("n").asInt()).isEqualTo(5);
    }
}

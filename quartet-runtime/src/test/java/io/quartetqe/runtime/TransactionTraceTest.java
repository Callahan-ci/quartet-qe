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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One W3C trace per test: every request Quartet sends carries it, and each act's hops reach the audit log. */
class TransactionTraceTest {
    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<String> pageRequests = new CopyOnWriteArrayList<>();
    private ScriptLlm llm;
    private TestAgent agent;

    private static final class ScriptLlm implements Llm.Client {
        final Deque<Llm.ToolCall> calls = new ArrayDeque<>();

        void add(String name, JsonNode args) {
            calls.add(new Llm.ToolCall("c" + calls.size(), name, args));
        }

        @Override
        public Llm.Response complete(Llm.Request request) {
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
            received.add(String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
            byte[] body = "{\"orderId\":\"ORD-12345678\",\"status\":\"RESERVED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("POST") ? 201 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            pageRequests.add(String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
            byte[] body = "<html><body><h1>Shop</h1></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path env = dir.resolve("context/environments/t.yaml");
        Files.createDirectories(env.getParent());
        Files.writeString(env, """
            name: t
            guard: {allowHosts: [127.0.0.1]}
            targets:
              api: {type: api, description: d, baseUrl: "http://127.0.0.1:%1$d"}
              site:
                type: screen
                description: d
                baseUrl: "http://127.0.0.1:%1$d"
                devices:
                  desktop: {kind: web, engine: chromium}
            """.formatted(server.getAddress().getPort()));
        llm = new ScriptLlm();
        agent = new TestAgent(Environment.load(env, Map.of()), () -> llm, TestAgent.Mode.AUTO,
                new ActRecorder(dir.resolve("cache"), dir.resolve("report.jsonl")), "T.m", "S-1", null, true);
    }

    @AfterEach
    void stop() throws Exception {
        agent.close();
        server.stop(0);
    }

    private static JsonNode finish(JsonNode data) {
        var finish = Json.obj().put("ok", true).put("summary", "done");
        finish.set("data", data);
        return finish;
    }

    private List<JsonNode> audit() throws IOException {
        return Files.readAllLines(dir.resolve("report.jsonl")).stream().map(Json::parse).toList();
    }

    @Test
    void aTraceparentIsW3cAndCarriesTheTestsTraceWithAFreshSpan() {
        TransactionTrace trace = new TransactionTrace();
        String first = trace.traceparent();
        String second = trace.traceparent();

        assertThat(first).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(TransactionTrace.traceIdOf(first)).isEqualTo(trace.traceId());
        assertThat(TransactionTrace.traceIdOf(second)).isEqualTo(trace.traceId());
        assertThat(first.substring(36, 52)).isNotEqualTo(second.substring(36, 52));
        assertThat(new TransactionTrace().traceId()).isNotEqualTo(trace.traceId());
    }

    @Test
    void malformedOrInvalidTraceHeadersCarryNoTrace() {
        assertThat(TransactionTrace.traceIdOf(null)).isNull();
        assertThat(TransactionTrace.traceIdOf("not a trace")).isNull();
        assertThat(TransactionTrace.traceIdOf("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01")).isNull();
        assertThat(TransactionTrace.traceIdOf("00-4bf92f3577b34da6a3ce929d0e0e4736-" + "0".repeat(16) + "-01")).isNull();
        assertThat(TransactionTrace.traceIdOf("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")).isNull();
        assertThat(TransactionTrace.traceIdOf(" 00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01 "))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThatThrownBy(() -> new TransactionTrace("0".repeat(32))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyOutputThatCarriedTheSameTraceCountsAsReached() {
        TransactionTrace trace = new TransactionTrace("4bf92f3577b34da6a3ce929d0e0e4736");
        trace.sent("orders-api", "http", "POST /api/orders → 201");
        trace.seen("order-events", "kafka", "event on shop.orders.created", "4bf92f3577b34da6a3ce929d0e0e4736");
        trace.seen("audit-events", "kafka", "event on shop.audit", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        trace.seen("late-events", "kafka", "event on shop.late", null);
        trace.read("orders-db", "postgres", "pg_query → rows:some");

        assertThat(trace.reached("order-events")).isTrue();
        assertThat(trace.reached("orders-api")).as("sending the trace does not prove the service passed it on").isFalse();
        assertThat(trace.reached("audit-events")).isFalse();
        assertThat(trace.reached("late-events")).isFalse();
        assertThat(trace.reached("orders-db")).isFalse();
        assertThat(trace.targetsReached()).containsExactly("order-events");
        assertThat(trace.drain()).hasSize(5);
        assertThat(trace.drain()).as("hops are written once, to the act that made them").isEmpty();
    }

    @Test
    void everyApiRequestInATestCarriesTheSameTraceAndTheAuditRecordsTheTransaction() throws IOException {
        llm.add("http_request", Json.obj().put("method", "POST").put("path", "/orders"));
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("orderId", "ORD-12345678").put("status", "RESERVED")));
        ActResult order = agent.act("Place an order", why("checkout"), on("api"), returns("orderId", "status"));
        llm.add("http_request", Json.obj().put("method", "GET").put("path", "/orders/ORD-12345678?fields=status"));
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        agent.act("Read order {{orderId}}", with("orderId", order.get("orderId")), why("it was kept"), on("api"), returns("status"));

        assertThat(received).hasSize(2);
        assertThat(received).allSatisfy(header -> assertThat(TransactionTrace.traceIdOf(header)).isEqualTo(agent.trace().traceId()));
        assertThat(received.get(0)).isNotEqualTo(received.get(1));

        List<JsonNode> lines = audit();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).path("traceId").asText()).isEqualTo(agent.trace().traceId());
        assertThat(lines.get(0).path("types").path("api").asText()).isEqualTo("api");
        assertThat(lines.get(0).path("data").path("orderId").asText()).isEqualTo("ORD-12345678");
        assertThat(lines.get(0).path("hops").get(0).path("link").asText()).isEqualTo("SENT");
        assertThat(lines.get(0).path("hops").get(0).path("detail").asText()).isEqualTo("POST /orders → 201");
        assertThat(lines.get(1).path("vars").path("orderId").asText()).isEqualTo("ORD-12345678");
        assertThat(lines.get(1).path("hops").get(0).path("detail").asText()).as("the query string stays out of the report").isEqualTo("GET /orders/ORD-12345678 → 200");
    }

    @Test
    void aReplayedActSendsTheTraceTooBecauseTheDriverAddsItNotTheRecording() throws IOException {
        llm.add("http_request", Json.obj().put("method", "POST").put("path", "/orders"));
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        agent.act("Place an order", why("checkout"), on("api"), returns("status"));
        agent.testPassed();

        String recording = Files.readString(Files.list(dir.resolve("cache/_shared")).findFirst().orElseThrow());
        assertThat(recording).doesNotContain("traceparent").doesNotContain(agent.trace().traceId());

        TestAgent second = new TestAgent(Environment.load(dir.resolve("context/environments/t.yaml"), Map.of()), () -> llm, TestAgent.Mode.REPLAY,
                new ActRecorder(dir.resolve("cache"), dir.resolve("report2.jsonl")), "T.m", "S-1", null, true);
        second.act("Place an order", why("checkout"), on("api"), returns("status"));
        assertThat(TransactionTrace.traceIdOf(received.get(1))).isEqualTo(second.trace().traceId()).isNotEqualTo(agent.trace().traceId());
    }

    @Test
    void aHeaderTheTestSetsItselfIsLeftAlone() {
        String own = "00-11111111111111111111111111111111-2222222222222222-01";
        llm.add("http_request", Json.obj().put("method", "GET").put("path", "/orders/1").set("headers", Json.obj().put("TraceParent", own)));
        llm.add(AgentLoop.FINISH, finish(Json.obj().put("status", "RESERVED")));
        agent.act("Read order 1", why("status"), on("api"), returns("status"));

        assertThat(received).containsExactly(own);
    }

    @Test
    void pageRequestsToTheAppCarryTheTraceAndADatabaseOrScreenStepIsNotedAsARead() throws IOException {
        llm.add("screen_open", Json.obj().put("path", "/"));
        llm.add(AgentLoop.FINISH, finish(Json.obj()));
        agent.act("Open the shop", why("arrivals"), on("site"));

        assertThat(pageRequests).isNotEmpty();
        assertThat(TransactionTrace.traceIdOf(pageRequests.get(0))).isEqualTo(agent.trace().traceId());
        JsonNode hop = audit().get(0).path("hops").get(0);
        assertThat(hop.path("target").asText()).isEqualTo("site");
        assertThat(hop.path("link").asText()).isEqualTo("READ");
    }

    @Test
    void theBrowserOnlySendsTheTraceToTheAppUnderTest() {
        BrowserSurface surface = new BrowserSurface("http://127.0.0.1:8080", Json.obj().put("kind", "web"), true, host -> true);
        assertThat(surface.sameApp("http://127.0.0.1:8080/api/orders")).isTrue();
        assertThat(surface.sameApp("http://127.0.0.1:9090/")).isFalse();
        assertThat(surface.sameApp("https://127.0.0.1:8080/")).isFalse();
        assertThat(surface.sameApp("https://cdn.example.com/app.js")).isFalse();
        assertThat(surface.sameApp("data:image/png;base64,AAAA")).isFalse();
        BrowserSurface https = new BrowserSurface("https://shop.example.com", Json.obj().put("kind", "web"), true, host -> true);
        assertThat(https.sameApp("https://shop.example.com:443/basket")).isTrue();
    }
}

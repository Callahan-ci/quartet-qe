package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;

import io.quartetqe.core.Json;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreflightTest {
    @Test
    void readsHostsAndPortsFromUrlsDatabaseAddressesAndBrokerLists() {
        assertThat(Preflight.address("https://www.example.com")).containsExactly(Map.entry("www.example.com", 443));
        assertThat(Preflight.address("http://localhost:18080/x")).containsExactly(Map.entry("localhost", 18080));
        assertThat(Preflight.address("jdbc:postgresql://db.internal:5432/orders")).containsExactly(Map.entry("db.internal", 5432));
        assertThat(Preflight.address("mongodb://mongo:27017")).containsExactly(Map.entry("mongo", 27017));
        assertThat(Preflight.address("k1:9092,k2:9093")).containsExactly(Map.entry("k1", 9092), Map.entry("k2", 9093));
    }

    @Test
    void namesOnlyTheTargetsThatDoNotAnswer() throws Exception {
        try (ServerSocket open = new ServerSocket(0)) {
            int closed;
            try (ServerSocket probe = new ServerSocket(0)) {
                closed = probe.getLocalPort();
            }
            var targets = Map.of(
                    "up", Json.parse("{\"baseUrl\":\"http://127.0.0.1:" + open.getLocalPort() + "\"}"),
                    "down", Json.parse("{\"baseUrl\":\"http://127.0.0.1:" + closed + "\"}"),
                    "screenOnly", Json.parse("{\"devices\":{\"desktop\":{\"kind\":\"web\"}}}"));

            assertThat(Preflight.unreachable(targets, 500)).containsExactly("down (127.0.0.1:" + closed + ")");
        }
    }
}

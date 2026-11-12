package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Before a test run: can every system the tests talk to actually be reached? */
final class Preflight {
    private static final Pattern HOST_PORT = Pattern.compile("(?:^|//|,)\\s*([A-Za-z0-9.\\-_\\[\\]:]+?):(\\d{2,5})(?=[/,?]|$)");

    private Preflight() {}

    /** Targets that cannot be reached, as "name (host:port)". Screens on phones and brokers are checked by address only. */
    static List<String> unreachable(Map<String, JsonNode> targets, int timeoutMs) {
        List<String> down = new ArrayList<>();
        for (var entry : targets.entrySet()) {
            Map<String, Integer> ports = new LinkedHashMap<>();
            for (String field : List.of("baseUrl", "url", "brokers")) {
                String value = entry.getValue().path(field).asText("");
                if (value.isBlank() || value.startsWith("jdbc:h2") || value.startsWith("file:")) continue;
                address(value).forEach(ports::putIfAbsent);
            }
            for (var address : ports.entrySet()) {
                if (!reachable(address.getKey(), address.getValue(), timeoutMs)) {
                    down.add(entry.getKey() + " (" + address.getKey() + ":" + address.getValue() + ")");
                    break;
                }
            }
        }
        return down;
    }

    static Map<String, Integer> address(String value) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("ws://") || value.startsWith("wss://")) {
            try {
                URI uri = URI.create(value);
                if (uri.getHost() != null) {
                    int port = uri.getPort() > 0 ? uri.getPort() : value.startsWith("http://") || value.startsWith("ws://") ? 80 : 443;
                    out.put(uri.getHost(), port);
                }
            } catch (IllegalArgumentException ignored) {
                // An address this check cannot read is left for the test run to report.
            }
            return out;
        }
        Matcher match = HOST_PORT.matcher(value);
        while (match.find()) out.putIfAbsent(match.group(1), Integer.parseInt(match.group(2)));
        return out;
    }

    private static boolean reachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }
}

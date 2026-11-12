package io.quartetqe.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An answer may name a number after what it is, such as bookCount, but never borrow a value from an unrelated field. */
class BinderNamesTest {
    private static List<String> ungrounded(String data, String toolResult) {
        return Binder.bindData(Json.parse(data), Map.<String, JsonNode>of(), List.of(Json.parse(toolResult))).ungrounded();
    }

    @Test
    void aPrefixedNameStillReadsTheToolsValue() {
        String result = "{\"count\":20,\"status\":409,\"price\":51.77}";
        assertThat(ungrounded("{\"bookCount\":20}", result)).isEmpty();
        assertThat(ungrounded("{\"httpStatus\":409}", result)).isEmpty();
        assertThat(ungrounded("{\"unitPrice\":51.77}", result)).isEmpty();
        assertThat(ungrounded("{\"count\":20}", result)).as("the exact name still works").isEmpty();
    }

    @Test
    void aNumberThatOnlyEqualsAnUnrelatedFieldIsStillRefused() {
        String result = "{\"count\":20,\"durationMs\":42,\"body\":{\"total\":39.98}}";
        assertThat(ungrounded("{\"total\":20}", result)).containsExactly("total=20");
        assertThat(ungrounded("{\"stock\":42}", result)).containsExactly("stock=42");
        assertThat(ungrounded("{\"bookCount\":21}", result)).as("an invented number").containsExactly("bookCount=21");
        assertThat(ungrounded("{\"ab\":20}", "{\"count\":20,\"a\":20}")).as("very short names never count as related").containsExactly("ab=20");
    }
}

package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

/** Expands {@link OnEverySurface}. A device that is not connected shows as skipped, with the reason. */
final class SurfaceInvocations implements TestTemplateInvocationContextProvider {
    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return context.getRequiredTestMethod().isAnnotationPresent(OnEverySurface.class);
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
        Map<String, String> env = System.getenv();
        Environment environment = Environment.load(Path.of(env.getOrDefault("QUARTET_ENV", "../context/environments/demo.yaml")), env);
        OnEverySurface annotation = context.getRequiredTestMethod().getAnnotation(OnEverySurface.class);
        List<String> kinds = List.of(annotation.kinds());
        JsonNode devices = environment.target(annotation.value()).path("devices");
        List<TestTemplateInvocationContext> out = new ArrayList<>();
        String method = context.getRequiredTestMethod().getName();
        devices.fields().forEachRemaining(entry -> {
            if (kinds.isEmpty() || kinds.contains(entry.getValue().path("kind").asText("web"))) {
                out.add(invocation(method, entry.getKey(), unavailableReason(entry.getValue())));
            }
        });
        return out.stream();
    }

    private static String unavailableReason(JsonNode device) {
        return switch (device.path("kind").asText("web")) {
            case "android" -> AndroidBridge.available() ? null : "no Android device or emulator is connected";
            case "ios" -> device.path("enabled").asBoolean(false) ? null : "the iOS runner is not enabled for this device";
            default -> null;
        };
    }

    private static TestTemplateInvocationContext invocation(String method, String device, String unavailable) {
        return new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(int index) {
                return method + " [" + device + "]";
            }

            @Override
            public List<Extension> getAdditionalExtensions() {
                BeforeEachCallback bind = context -> context.getStore(AgentExtension.NS).put("device", device);
                ExecutionCondition condition = context -> unavailable == null
                        ? ConditionEvaluationResult.enabled("available")
                        : ConditionEvaluationResult.disabled(device + " skipped: " + unavailable);
                return List.of(bind, condition);
            }
        };
    }
}

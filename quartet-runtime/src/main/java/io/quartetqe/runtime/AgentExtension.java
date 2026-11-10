package io.quartetqe.runtime;

import io.quartetqe.core.Llm;
import io.quartetqe.core.LlmFactory;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Injects a {@link TestAgent} into test methods. The AI is only contacted if a step needs it. */
public final class AgentExtension implements ParameterResolver, AfterEachCallback {
    static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(AgentExtension.class);
    private static final Supplier<Llm.Client> LLM = new Supplier<>() {
        private Llm.Client client;

        @Override
        public synchronized Llm.Client get() {
            if (client == null) client = LlmFactory.fromEnv();
            return client;
        }
    };

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == TestAgent.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Map<String, String> env = System.getenv();
        Environment environment = Environment.load(Path.of(env.getOrDefault("QUARTET_ENV", "../context/environments/demo.yaml")), env);
        TestAgent.Mode mode = TestAgent.Mode.valueOf(env.getOrDefault("QUARTET_MODE", "replay").toUpperCase());
        ActRecorder recorder = new ActRecorder(Path.of(env.getOrDefault("QUARTET_RECORDINGS", "recordings")),
                Path.of(env.getOrDefault("QUARTET_REPORT", "build/quartet-report/acts.jsonl")));
        Story story = context.getRequiredTestClass().getAnnotation(Story.class);
        String device = context.getStore(NS).get("device", String.class);
        if (device == null) device = env.get("QUARTET_DEVICE");
        String testId = context.getRequiredTestClass().getSimpleName() + "." + context.getRequiredTestMethod().getName() + (device == null ? "" : "@" + device);
        TestAgent agent = new TestAgent(environment, LLM, mode, recorder, testId, story == null ? "none" : story.value(), device,
                !"false".equals(env.get("QUARTET_HEADLESS")));
        context.getStore(NS).put("agent", agent);
        return agent;
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        TestAgent agent = context.getStore(NS).remove("agent", TestAgent.class);
        if (agent == null) return;
        if (context.getExecutionException().isEmpty()) agent.testPassed();
        else agent.testFailed();
        agent.close();
    }
}

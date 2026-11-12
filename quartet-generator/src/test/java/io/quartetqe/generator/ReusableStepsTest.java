package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reusable steps (Quartet's page objects) keep tests short, and the rules see through them. */
class ReusableStepsTest {
    private static final Path CONTEXT = Path.of("../context");
    private final StaticRules rules = new StaticRules(Principles.load(CONTEXT), new ContextRepo(CONTEXT), Set.of("orders-api", "orders-db"));
    private final WorkItem story = WorkItem.of("S-1", "T", "## Acceptance criteria\n\n- AC1: One thing works.\n", "");

    private static final String STEPS = """
        package steps;
        import static io.quartetqe.runtime.Act.*;
        import io.quartetqe.runtime.*;
        public final class Orders {
            private final TestAgent agent;
            public Orders(TestAgent agent) { this.agent = agent; }
            public ActResult placeOrder() {
                return agent.act("Place an order for SKU-1001", why("Customers must be able to buy"), on("orders-api"), returns("status"));
            }
            public ActResult findOrder() {
                return agent.act("Find the order in the database", why("The saved order is the source of truth"), on("orders-db"), returns("status"));
            }
            public void tenOrders() {
                for (int i = 0; i < 10; i++) placeOrder();
            }
        }
        """;

    private static String testClass(String name, String body) {
        return """
            package generated.t;
            import static io.quartetqe.runtime.Act.*;
            import static org.assertj.core.api.Assertions.assertThat;
            import io.quartetqe.runtime.*;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.extension.ExtendWith;
            import steps.Orders;
            @Story("S-1")
            @ExtendWith(AgentExtension.class)
            class %s {
                @Test @WalkingSkeleton @AcceptanceCriteria("AC1")
                void t(TestAgent agent) throws Exception {
                    %s
                }
            }
            """.formatted(name, body);
    }

    private static final String USES_STEPS = "ActResult o = new Orders(agent).placeOrder(); assertThat(o.get(\"status\")).isEqualTo(\"RESERVED\");"
            + " ActResult s = new Orders(agent).findOrder(); assertThat(s.get(\"status\")).isEqualTo(\"RESERVED\");";

    private List<String> rulesFor(Map<String, String> sources) {
        return rules.check(sources, story).stream().map(Finding::rule).toList();
    }

    /** Findings that stop a story. The telemetry and latency notes appear for any small test and are not what is under test here. */
    private List<String> blockingFor(Map<String, String> sources) {
        return rules.check(sources, story).stream().filter(Finding::blocking).map(Finding::rule).toList();
    }

    @Test
    void aStepsClassNeedsNoStoryWiringAndTheSkeletonSeesTheLayersInsideItsSteps() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("T.java", testClass("T", USES_STEPS));
        sources.put("steps/Orders.java", STEPS);

        assertThat(blockingFor(sources)).as("compiles with the steps, no wiring complaint, skeleton crosses API and database").isEmpty();
    }

    @Test
    void aSkeletonThatCallsOnlyOneLayerIsStillCaughtEvenThroughSteps() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("T.java", testClass("T", "ActResult o = new Orders(agent).placeOrder(); assertThat(o.get(\"status\")).isEqualTo(\"RESERVED\");"));
        sources.put("steps/Orders.java", STEPS);

        assertThat(blockingFor(sources)).containsExactly("PROD-WALKING-SKELETON");
    }

    @Test
    void theRulesStillApplyInsideSteps() {
        String brittle = STEPS.replace("Place an order for SKU-1001", "Click #buy-button");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("T.java", testClass("T", USES_STEPS));
        sources.put("steps/Orders.java", brittle);

        assertThat(rulesFor(sources)).contains("EVOL-INTENT");
    }

    @Test
    void aCallToAStepCountsAsOneStepHoweverManyActsItPerformsInside() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("T.java", testClass("T", USES_STEPS + " new Orders(agent).tenOrders();"));
        one.put("steps/Orders.java", STEPS);
        assertThat(rulesFor(one)).as("ten acts inside one step is still one step").doesNotContain("SIMPLE-FOCUS");

        String many = "ActResult o = new Orders(agent).placeOrder(); assertThat(o.get(\"status\")).isEqualTo(\"RESERVED\");"
                + " new Orders(agent).findOrder();".repeat(9);
        Map<String, String> tooMany = new LinkedHashMap<>();
        tooMany.put("T.java", testClass("T", many));
        tooMany.put("steps/Orders.java", STEPS);
        assertThat(rulesFor(tooMany)).as("ten steps in one test is too many").contains("SIMPLE-FOCUS");
    }

    @Test
    void theSameActWrittenInTwoTestClassesIsSentToASharedStepButCallingTheStepIsFine() {
        String direct = "ActResult o = agent.act(\"Place an order for SKU-1001\", why(\"Customers must be able to buy\"), on(\"orders-api\"), returns(\"status\"));"
                + " assertThat(o.get(\"status\")).isEqualTo(\"RESERVED\");"
                + " ActResult s = agent.act(\"Find the order in the database\", why(\"The saved order is the source of truth\"), on(\"orders-db\"), returns(\"status\")); assertThat(s.get(\"status\")).isEqualTo(\"X\");";
        List<Finding> copied = rules.check(Map.of("A.java", testClass("A", direct), "B.java", testClass("B", direct)), story);
        assertThat(copied).anyMatch(f -> f.rule().equals("REUSE-EMERGENT") && f.message().contains("steps class"));

        Map<String, String> shared = new LinkedHashMap<>();
        shared.put("A.java", testClass("A", USES_STEPS));
        shared.put("B.java", testClass("B", USES_STEPS));
        shared.put("steps/Orders.java", STEPS);
        assertThat(rulesFor(shared)).as("reusing a step is not repetition").doesNotContain("REUSE-EMERGENT");
    }

    @Test
    void aStepsClassHoldsStepsOnlyAndIsWrittenToTheStepsPackage(@TempDir Path root) throws Exception {
        Map<String, String> steps = new LinkedHashMap<>();

        assertThat(StepsLibrary.write(root, steps, "Orders.java", STEPS).path("written").asText()).isEqualTo("steps/Orders.java");
        assertThat(Files.readString(root.resolve("steps/Orders.java"))).contains("placeOrder");
        assertThat(StepsLibrary.load(root)).containsOnlyKeys("steps/Orders.java");

        assertThatThrownBy(() -> StepsLibrary.write(root, steps, "../Evil.java", STEPS)).hasMessageContaining("class name");
        assertThatThrownBy(() -> StepsLibrary.write(root, steps, "X.java", "class X {}")).hasMessageContaining("package steps;");
        assertThatThrownBy(() -> StepsLibrary.write(root, steps, "X.java", "package steps;\nclass X { @Test void t() {} }")).hasMessageContaining("not tests");
    }

    @Test
    void theListOfStepsSaysWhatEachOneDoesSoTheAiReusesInsteadOfRewriting() {
        var description = StepsLibrary.describe(Map.of("steps/Orders.java", STEPS));

        assertThat(description.path("steps_classes").get(0).path("class").asText()).isEqualTo("Orders");
        assertThat(description.path("steps_classes").get(0).path("steps").toString()).contains("placeOrder").contains("Place an order for SKU-1001 [orders-api]");
        assertThat(description.path("how_to_use").asText()).contains("Reuse these");
        assertThat(StepsLibrary.describe(Map.of()).path("how_to_use").asText()).contains("No steps classes yet");
    }

    @Test
    void aProblemInsideAStepIsReportedAtThatStepNotAtTheTestThatCallsIt() {
        String brittle = STEPS.replace("Place an order for SKU-1001", "Click #buy-button");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("T.java", testClass("T", USES_STEPS));
        sources.put("steps/Orders.java", brittle);

        List<Finding> intent = rules.check(sources, story).stream().filter(f -> f.rule().equals("EVOL-INTENT")).toList();

        assertThat(intent).hasSize(1);
        assertThat(intent.get(0).location()).startsWith("steps/Orders.java:");
    }

    @Test
    void aStepMayTakeItsReasonAsAParameterButAShortWrittenReasonIsStillRefused() {
        String parameterised = STEPS.replace("public ActResult placeOrder() {", "public ActResult placeOrder(String reason) {")
                .replace("why(\"Customers must be able to buy\")", "why(reason)")
                .replace("for (int i = 0; i < 10; i++) placeOrder();", "");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("T.java", testClass("T", USES_STEPS.replace("placeOrder()", "placeOrder(\"Customers must be able to buy\")")));
        sources.put("steps/Orders.java", parameterised);
        assertThat(blockingFor(sources)).as("why(reason) cannot be read, so the caller's text is trusted").isEmpty();

        String tooShort = STEPS.replace("why(\"Customers must be able to buy\")", "why(\"buying\")");
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("T.java", testClass("T", USES_STEPS));
        bad.put("steps/Orders.java", tooShort);
        assertThat(blockingFor(bad)).contains("OBS-WHY");
    }
}

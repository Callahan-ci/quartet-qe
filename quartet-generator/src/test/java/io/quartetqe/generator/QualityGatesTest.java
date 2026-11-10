package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Uses the real context repo, so these rules are tested as a team would receive them. */
class QualityGatesTest {
    private static final Path CONTEXT = Path.of("../context");
    private final ContextRepo context = new ContextRepo(CONTEXT);
    private final Principles principles = Principles.load(CONTEXT);
    private final StaticRules rules = new StaticRules(principles, context, Set.of("orders-api", "orders-db"));
    private final IntentGuard guard = new IntentGuard(context);

    private static final String HEADER = """
        package generated.t;
        import static io.quartetqe.runtime.Act.*;
        import static org.assertj.core.api.Assertions.assertThat;
        import io.quartetqe.runtime.*;
        import org.junit.jupiter.api.Test;
        import org.junit.jupiter.api.extension.ExtendWith;
        @Story("S-1")
        @ExtendWith(AgentExtension.class)
        class T {
        """;

    private static String test(String body) {
        return HEADER + "@Test @AcceptanceCriteria(\"AC1\") void t(TestAgent agent) throws Exception {\n" + body + "\n}\n}\n";
    }

    private static final String GOOD_ACT = "ActResult r = agent.act(\"Place an order for SKU-1001\", why(\"Customers must be able to buy\"), on(\"orders-api\"), returns(\"status\"));\n";

    private List<String> ruleIds(String source) {
        return rules.check(Map.of("T.java", source), null).stream().map(Finding::rule).toList();
    }

    @Test
    void aCleanTestHasNoFindings() {
        assertThat(ruleIds(test(GOOD_ACT + "assertThat(r.get(\"status\")).isEqualTo(\"RESERVED\");"))).isEmpty();
    }

    @Test
    void compileErrorsAreReported() {
        assertThat(ruleIds(test("int x = \"nope\";"))).contains("AUTO-COMPILE");
    }

    @Test
    void brittleAndUnsafePatternsAreCaught() {
        assertThat(ruleIds(test(GOOD_ACT + "Thread.sleep(100); assertThat(r.get(\"status\")).isEqualTo(\"A\");"))).contains("PERF-NO-SLEEP");
        assertThat(ruleIds(test("ActResult r = agent.act(\"Click #buy-button\", why(\"Customers must be able to buy\"), on(\"orders-api\")); assertThat(r.get(\"a\")).isEqualTo(\"b\");"))).contains("EVOL-INTENT");
        assertThat(ruleIds(test(GOOD_ACT + "String u = \"http://localhost:1\"; assertThat(r.get(\"a\")).isEqualTo(\"b\");"))).contains("PROD-ENV-AGNOSTIC");
        assertThat(ruleIds(test(GOOD_ACT + "String e = \"real.person@gmail.com\"; assertThat(r.get(\"a\")).isEqualTo(\"b\");"))).contains("DATA-NO-PII");
        assertThat(ruleIds(test(GOOD_ACT + "String k = \"password=hunter2\"; assertThat(r.get(\"a\")).isEqualTo(\"b\");"))).contains("SEC-NO-SECRETS");
    }

    @Test
    void weakOrMissingOraclesAreCaught() {
        assertThat(ruleIds(test(GOOD_ACT))).contains("ASSERT-ORACLE");
        assertThat(ruleIds(test(GOOD_ACT + "assertThat(r.get(\"status\")).isNotNull();"))).contains("ASSERT-STRENGTH");
        assertThat(ruleIds(test(GOOD_ACT + "try { assertThat(r.get(\"a\")).isEqualTo(\"b\"); } catch (AssertionError e) {}"))).contains("ASSERT-NO-SWALLOW");
    }

    @Test
    void inventedFactsAndTargetsAreCaught() {
        assertThat(ruleIds(test("ActResult r = agent.act(\"Call /api/refunds for SKU-9999\", why(\"Customers must be able to buy\"), on(\"orders-api\")); assertThat(r.get(\"a\")).isEqualTo(\"b\");"))).contains("GROUND-KNOWN");
        assertThat(ruleIds(test("ActResult r = agent.act(\"Do a thing\", why(\"Customers must be able to buy\"), on(\"made-up\")); assertThat(r.get(\"a\")).isEqualTo(\"b\");"))).contains("GROUND-KNOWN");
    }

    @Test
    void aWhyThatExplainsNothingIsCaught() {
        assertThat(ruleIds(test("ActResult r = agent.act(\"Do a thing\", why(\"Checkout\"), on(\"orders-api\")); assertThat(r.get(\"a\")).isEqualTo(\"b\");"))).contains("OBS-WHY");
    }

    @Test
    void storyNeedsExactlyOneWalkingSkeleton() {
        WorkItem story = WorkItem.of("S-1", "t", "- AC1: returns 201", "u");
        String source = test(GOOD_ACT + "assertThat(r.get(\"status\")).isEqualTo(\"A\");");
        assertThat(rules.check(Map.of("T.java", source), story).stream().map(Finding::rule)).contains("PROD-WALKING-SKELETON");
    }

    @Test
    void vagueStoriesRaiseQuestionsAndOnlyMissingCriteriaStopWork() {
        WorkItem vague = WorkItem.of("S", "t", "- AC1: The page loads quickly\n- AC2: Discounts work properly", "u");
        assertThat(StoryReadiness.check(vague, principles)).isNotEmpty().noneMatch(Finding::blocking);

        WorkItem none = WorkItem.of("S", "t", "Make it better", "u");
        assertThat(StoryReadiness.check(none, principles)).extracting(Finding::rule).containsExactly("READY-CRITERIA");

        WorkItem good = WorkItem.of("S", "t", "As a user, so that I buy\n- AC1: Returns 201\n- AC2: Invalid orders are rejected with 400", "u");
        assertThat(StoryReadiness.check(good, principles)).isEmpty();
    }

    @Test
    void criteriaAreParsedFromNamedAndBulletFormats() {
        assertThat(WorkItem.parseCriteria("- AC1: One\n**AC-2**: Two")).containsOnlyKeys("AC1", "AC2");
        assertThat(WorkItem.parseCriteria("Acceptance criteria\n- First\n- Second")).containsEntry("AC2", "Second");
    }

    private static final String ORIGINAL = test(GOOD_ACT + "assertThat(r.get(\"status\")).isEqualTo(\"RESERVED\");");

    @Test
    void repairsMayChangeHowATestWorks() {
        String rewritten = test("ActResult r = agent.act(\"Buy two units\", why(\"Different wording is fine\"), on(\"orders-api\"), returns(\"status\"));\nassertThat(r.get(\"status\")).isEqualTo(\"RESERVED\");");
        assertThat(guard.violations("T.java", ORIGINAL, rewritten, List.of())).isEmpty();
    }

    @Test
    void repairsMayNotWeakenWhatATestProves() {
        assertThat(guard.violations("T.java", ORIGINAL, test(GOOD_ACT), List.of())).isNotEmpty();
        assertThat(guard.violations("T.java", ORIGINAL, ORIGINAL.replace("void t(", "void renamed("), List.of())).isNotEmpty();
        assertThat(guard.violations("T.java", ORIGINAL, ORIGINAL.replace("@Test", "@Test @org.junit.jupiter.api.Disabled"), List.of())).isNotEmpty();
        assertThat(guard.violations("T.java", ORIGINAL, ORIGINAL.replace("@Story(", "@org.junit.jupiter.api.Disabled @Story("), List.of())).isNotEmpty();
        assertThat(guard.violations("T.java", ORIGINAL, ORIGINAL.replace("@Test", "@Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = \"NEVER\", matches = \"x\")"), List.of())).isNotEmpty();
        assertThat(guard.violations("T.java", ORIGINAL, ORIGINAL.replace("assertThat(r.get(\"status\")).isEqualTo(\"RESERVED\");", "try { assertThat(r.get(\"status\")).isEqualTo(\"RESERVED\"); } catch (AssertionError e) {}"), List.of())).isNotEmpty();
    }

    @Test
    void expectedValuesChangeOnlyWithDocumentedEvidence() {
        String changed = ORIGINAL.replace("isEqualTo(\"RESERVED\")", "isEqualTo(\"PAID\")");
        assertThat(guard.violations("T.java", ORIGINAL, changed, List.of())).isNotEmpty();
        assertThat(guard.violations("T.java", ORIGINAL, changed,
                List.of(new IntentGuard.Evidence("RESERVED", "NOT-IN-ANY-CONTRACT", "contracts/openapi/orders-service.yaml")))).isNotEmpty();
        assertThat(guard.violations("T.java", ORIGINAL, changed,
                List.of(new IntentGuard.Evidence("RESERVED", "PAID", "contracts/openapi/orders-service.yaml")))).isEmpty();
    }

    private static TestRunner.Result run(boolean passed, String failure, String log) {
        return new TestRunner.Result(passed ? 0 : 1, List.of(new TestRunner.TestOutcome("C", "m", passed, failure)), log);
    }

    @Test
    void failuresAreDiagnosedBeforeAnythingIsChanged() {
        var green = run(true, null, "");
        var assertion = run(false, "org.opentest4j.AssertionFailedError: expected RESERVED", "");
        var refused = run(false, "java.net.ConnectException: Connection refused", "");
        var noModel = run(false, "LlmException: No AI configured", "");
        Finding brittle = principles.finding("PERF-NO-SLEEP", "x", "y").orElseThrow();

        assertThat(BrittleRepair.classify(green, green, List.of())).isEqualTo(BrittleRepair.Diagnosis.HEALTHY);
        assertThat(BrittleRepair.classify(green, green, List.of(brittle))).isEqualTo(BrittleRepair.Diagnosis.BRITTLE);
        assertThat(BrittleRepair.classify(assertion, assertion, List.of())).isEqualTo(BrittleRepair.Diagnosis.POSSIBLE_PRODUCT_DEFECT);
        assertThat(BrittleRepair.classify(assertion, green, List.of())).isEqualTo(BrittleRepair.Diagnosis.FLAKY);
        assertThat(BrittleRepair.classify(refused, refused, List.of())).isEqualTo(BrittleRepair.Diagnosis.ENVIRONMENT);
        assertThat(BrittleRepair.classify(noModel, noModel, List.of())).isEqualTo(BrittleRepair.Diagnosis.MODEL_UNAVAILABLE);
    }

    @Test
    void everyArticlePrincipleIsPresentAndHasARule() {
        assertThat(principles.names()).contains("Design for emergent reuse", "Build evolutionary systems to enable easy incremental change",
                "Small and simple reduce complexity ensure testable", "Decouple and be cohesive as possible", "Data stewardship ensure security and protection",
                "Production ready from the start", "Secure by design", "Automation by default", "Understandability and Observability",
                "Performance and Reliability", "Feedback early and Often");
    }

    @Test
    void contextSearchFindsTheRightFile() {
        assertThat(context.search("orders_created_total objective latency", 3).get(0).path()).isEqualTo("telemetry/orders-service.yaml");
    }
}

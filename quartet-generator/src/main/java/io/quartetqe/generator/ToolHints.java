package io.quartetqe.generator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * What each tool Quartet offers over MCP does to the outside world, so a chat app can ask the user before the risky ones.
 * These are the MCP tool annotations. They are honest about side effects: when unsure, a tool is described as the
 * riskier thing. A tool with no entry here is treated as the riskiest kind until someone classifies it.
 *
 * readOnly: changes nothing on disk or anywhere else. destructive: can replace or change existing things (only
 * meaningful when not read-only). idempotent: the same call again has no further effect. openWorld: talks to systems
 * beyond this computer, such as a tracker, a git host, Jenkins or the system under test.
 */
final class ToolHints {
    record Hints(String title, boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {}

    private static final Hints UNKNOWN = new Hints(null, false, true, false, true);

    private static final Map<String, Hints> BY_TOOL = Map.ofEntries(
            // Saves the story and, for a site, its environment files, then reads trackers or the site. Adds files, never replaces tests.
            Map.entry("quartet_start", new Hints("Start on a story", false, false, false, true)),
            // Creates a new tests repo (refuses an existing folder), saves the setup choices, and can create a Jenkins job.
            Map.entry("quartet_setup", new Hints("Save the setup answers", false, false, false, true)),
            Map.entry("quartet_onboarding", new Hints("Show the setup checklist", true, false, true, false)),
            // Replaces a settings section, and can download a schema file from a link.
            Map.entry("quartet_configure", new Hints("Write part of the setup", false, true, true, true)),
            Map.entry("quartet_secrets_status", new Hints("Show which secrets are saved", true, false, true, false)),
            // Reads one story, the repository or Jenkins to prove the connection works. Changes nothing.
            Map.entry("quartet_verify", new Hints("Check a connection", true, false, true, true)),
            Map.entry("context_list", new Hints("List context files", true, false, true, false)),
            Map.entry("context_search", new Hints("Search the context", true, false, true, false)),
            Map.entry("context_read", new Hints("Read a context file", true, false, true, false)),
            Map.entry("repo_list", new Hints("List files in a repository", true, false, true, true)),
            Map.entry("repo_read", new Hints("Read a file from a repository", true, false, true, true)),
            // Creates or replaces a generated test file.
            Map.entry("write_test", new Hints("Write a test", false, true, true, false)),
            Map.entry("list_steps", new Hints("List reusable steps", true, false, true, false)),
            Map.entry("write_steps", new Hints("Write reusable steps", false, true, true, false)),
            // Compiles the tests, which writes build output but changes no source.
            Map.entry("validate_tests", new Hints("Check the tests", false, false, true, false)),
            Map.entry("quartet_check_environment", new Hints("Check the systems are reachable", true, false, true, true)),
            // Runs the tests and the planted-bug proof against the real systems, which can create or change data there.
            Map.entry("quartet_finish", new Hints("Review and run the tests", false, true, false, true)),
            Map.entry("quartet_next_step", new Hints("Wait for the next step", true, false, false, false)),
            // The answer drives test steps that act on the system under test.
            Map.entry("quartet_answer_step", new Hints("Answer a step", false, true, false, true)));

    private ToolHints() {}

    static boolean classified(String tool) {
        return BY_TOOL.containsKey(tool);
    }

    /** Adds the annotations object to a tool listing entry. */
    static void annotate(ObjectNode tool) {
        Hints hints = BY_TOOL.getOrDefault(tool.path("name").asText(), UNKNOWN);
        ObjectNode annotations = tool.putObject("annotations");
        if (hints.title() != null) annotations.put("title", hints.title());
        annotations.put("readOnlyHint", hints.readOnly());
        if (!hints.readOnly()) annotations.put("destructiveHint", hints.destructive());
        annotations.put("idempotentHint", hints.idempotent());
        annotations.put("openWorldHint", hints.openWorld());
    }
}

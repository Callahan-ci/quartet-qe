package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The chat-app extension's manifest must describe what the server really offers, so the two cannot drift apart. */
class ExtensionManifestTest {
    private static final Path REPO = Path.of("").toAbsolutePath().getParent();
    @TempDir Path temp;

    private JsonNode manifest() throws IOException {
        return Json.parse(Files.readString(REPO.resolve("mcpb/manifest.json")));
    }

    @Test
    void listsExactlyTheToolsTheServerOffers() throws Exception {
        Path project = temp.resolve("p");
        Files.createDirectories(project);
        for (String name : List.of("quartet.yaml", "context", "stories")) copy(REPO.resolve("skeleton").resolve(name), project.resolve(name));
        List<Tool> offered = new ChatSession(new Main(project, null), project.resolve("build/quartet-host")).tools();
        Set<String> real = new TreeSet<>();
        offered.forEach(t -> real.add(t.name()));
        Set<String> declared = new TreeSet<>();
        manifest().path("tools").forEach(t -> declared.add(t.path("name").asText()));
        assertThat(declared).isEqualTo(real);
    }

    @Test
    void everySettingIsUsedAndEverythingUsedIsASetting() throws Exception {
        JsonNode manifest = manifest();
        Set<String> declared = new HashSet<>();
        manifest.path("user_config").fieldNames().forEachRemaining(declared::add);
        Set<String> used = new HashSet<>();
        manifest.path("server").path("mcp_config").path("env").forEach(v -> {
            var m = java.util.regex.Pattern.compile("\\$\\{user_config\\.([a-z_]+)}").matcher(v.asText());
            while (m.find()) used.add(m.group(1));
        });
        assertThat(used).isEqualTo(declared);
    }

    @Test
    void everyTokenIsMarkedSensitiveAndEveryVariableIsOneConnectorsReads() throws Exception {
        JsonNode manifest = manifest();
        String connectors = Files.readString(REPO.resolve("connectors.yaml"));
        manifest.path("server").path("mcp_config").path("env").fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            if (name.startsWith("QUARTET_")) return;
            assertThat(connectors).as(name + " is read by connectors.yaml").contains("${" + name + ":-");
            if (name.endsWith("_TOKEN")) {
                String key = entry.getValue().asText().replaceAll("^\\$\\{user_config\\.|}$", "");
                assertThat(manifest.path("user_config").path(key).path("sensitive").asBoolean()).as(name + " is sensitive").isTrue();
            }
        });
    }

    @Test
    void namesTheVersionPlaceholderTheBuildFillsIn() throws Exception {
        assertThat(manifest().path("version").asText()).isEqualTo("__VERSION__");
        assertThat(manifest().path("manifest_version").asText()).isEqualTo("0.3");
    }

    @Test
    void anExtensionInstallPointsTheUserToTheSettingsFormForTheTokensItHas() {
        String advice = Onboarding.secretAdvice(List.of("JIRA_TOKEN", "JENKINS_GIT_CREDENTIALS"), Path.of("/repo"), "extension");
        assertThat(advice).contains("extension's settings").contains("[JIRA_TOKEN]").contains("./quartet-qe secret JENKINS_GIT_CREDENTIALS");
        assertThat(advice).doesNotContain("./quartet-qe secret JIRA_TOKEN");
        assertThat(Onboarding.secretAdvice(List.of("JIRA_TOKEN"), Path.of("/repo"), null)).contains("./quartet-qe secret JIRA_TOKEN").doesNotContain("extension");
    }

    @Test
    void aPluginInstallPointsToThePluginSettings() {
        String advice = Onboarding.secretAdvice(List.of("GITHUB_TOKEN"), Path.of("/repo"), "plugin");
        assertThat(advice).contains("plugin's settings").contains("[GITHUB_TOKEN]").doesNotContain("extension");
    }

    private static void copy(Path from, Path to) throws IOException {
        try (var all = Files.walk(from)) {
            for (Path source : all.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }
}

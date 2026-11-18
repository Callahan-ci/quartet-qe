package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The directory plugin in plugin/ must keep to the directory's rules, which are checked when a version is submitted:
 * text files only, small files, nothing outside the folder, a launcher that is pinned and verified. Catching a slip here
 * is cheaper than a held or rejected version.
 */
class PluginLayoutTest {
    private static final Path REPO = Path.of("").toAbsolutePath().getParent();
    private static final Path PLUGIN = REPO.resolve("plugin");
    private static final Set<String> TEXT = Set.of("md", "json", "sh", "txt", "yaml", "yml");
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "webp");

    private JsonNode manifest() throws IOException {
        return Json.parse(Files.readString(PLUGIN.resolve(".claude-plugin/plugin.json")));
    }

    private List<Path> files() throws IOException {
        try (Stream<Path> all = Files.walk(PLUGIN)) {
            return all.filter(p -> !Files.isDirectory(p)).toList();
        }
    }

    @Test
    void theFolderIsSmallTextAndNothingThatTheDirectoryHoldsOrRefuses() throws Exception {
        List<Path> files = files();
        assertThat(files.size()).isLessThanOrEqualTo(512);
        for (Path file : files) {
            String name = file.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
            assertThat(Files.isSymbolicLink(file)).as(name + " is a link").isFalse();
            assertThat(name).as("system file").isNotIn(".DS_Store", "Thumbs.db", "desktop.ini");
            boolean image = IMAGES.contains(ext);
            assertThat(image || TEXT.contains(ext) || name.equals("LICENSE")).as(name + " must be text or an image").isTrue();
            if (!image) assertThat(Files.size(file)).as(name + " must be under 256 KiB").isLessThan(256 * 1024);
        }
        // A top-level bin/ folder stops claude.ai and Cowork from installing the plugin at all.
        assertThat(PLUGIN.resolve("bin")).doesNotExist();
    }

    @Test
    void theManifestIsCompleteAndSpecific() throws Exception {
        JsonNode m = manifest();
        assertThat(m.path("name").asText()).matches("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?").isNotIn("claude", "anthropic", "official", "plugin", "mcp", "test");
        assertThat(m.path("description").asText()).isNotBlank();
        assertThat(m.path("author").path("name").asText()).isNotBlank();
        assertThat(m.path("license").asText()).isEqualTo("MIT");
        assertThat(m.path("version").asText()).matches("\\d+\\.\\d+\\.\\d+");
        assertThat(PLUGIN.resolve("LICENSE")).exists();
        // The directory reads these for the listing: the icon (a square PNG, 512 to 2048 px) and three https links.
        assertThat(m.path("icon").asText()).isEqualTo("./.claude-plugin/icon.png");
        assertThat(PLUGIN.resolve(".claude-plugin/icon.png")).exists();
        java.awt.image.BufferedImage icon = javax.imageio.ImageIO.read(PLUGIN.resolve(".claude-plugin/icon.png").toFile());
        assertThat(icon.getWidth()).isEqualTo(icon.getHeight()).isBetween(512, 2048);
        assertThat(Files.size(PLUGIN.resolve(".claude-plugin/icon.png"))).isLessThan(2L * 1024 * 1024);
        for (String link : List.of("documentationUrl", "supportUrl", "privacyPolicyUrl")) assertThat(m.path(link).asText()).startsWith("https://");
    }

    @Test
    void theReadmeSaysWhatThePluginRunsSendsAndFetches() throws Exception {
        String readme = Files.readString(PLUGIN.resolve("README.md"));
        String prose = readme.replaceAll("(?s)```.*?```", " ");
        assertThat(prose.split("\\s+").length).isGreaterThanOrEqualTo(40);
        assertThat(readme).contains("What this plugin runs, sends and fetches").contains("SHA-256").contains("sends nothing to its author").contains("/plugin install");
    }

    @Test
    void theServerStartsOneFileInThePluginAndEverySettingIsUsedAndReadByTheConnectors() throws Exception {
        JsonNode server = Json.parse(Files.readString(PLUGIN.resolve(".mcp.json"))).path("mcpServers").path("quartet-qe");
        assertThat(server.path("command").asText()).isEqualTo("${CLAUDE_PLUGIN_ROOT}/scripts/start-quartet.sh");
        assertThat(server.path("args")).isEmpty();
        assertThat(PLUGIN.resolve("scripts/start-quartet.sh")).exists();

        JsonNode manifest = manifest();
        Set<String> declared = new HashSet<>();
        manifest.path("userConfig").fieldNames().forEachRemaining(declared::add);
        Set<String> used = new HashSet<>();
        String connectors = Files.readString(REPO.resolve("connectors.yaml"));
        server.path("env").fields().forEachRemaining(entry -> {
            Matcher m = Pattern.compile("\\$\\{user_config\\.([a-z_]+)}").matcher(entry.getValue().asText());
            while (m.find()) used.add(m.group(1));
            if (!entry.getKey().startsWith("QUARTET_")) assertThat(connectors).as(entry.getKey()).contains("${" + entry.getKey() + ":-");
            if (entry.getKey().endsWith("_TOKEN")) {
                String key = entry.getValue().asText().replaceAll("^\\$\\{user_config\\.|}$", "");
                assertThat(manifest.path("userConfig").path(key).path("sensitive").asBoolean()).as(key).isTrue();
            }
        });
        assertThat(used).isEqualTo(declared);
        // Every setting has a default, so a surface that does not prompt for values still loads the server.
        manifest.path("userConfig").forEach(option -> assertThat(option.has("default")).isTrue());
        assertThat(server.path("env").path("QUARTET_LAUNCHED_BY").asText()).isEqualTo("plugin");
    }

    @Test
    void theLauncherIsPinnedVerifiedAndRunsNothingItDidNotCheck() throws Exception {
        String script = Files.readString(PLUGIN.resolve("scripts/start-quartet.sh"));
        assertThat(script).startsWith("#!/bin/sh").contains("set -eu");
        assertThat(script).contains("sha256sum").contains("shasum -a 256").contains("does not match the checksum");
        // The checksum check comes before anything is unpacked or started.
        assertThat(script.indexOf("does not match the checksum")).isLessThan(script.indexOf("unzip -q"));
        assertThat(script.indexOf("unzip -q")).isLessThan(script.lastIndexOf("exec \"$ROOT/bin/quartet-qe\""));
        // Nothing is piped into a shell, and it never evaluates text it fetched.
        assertThat(script).doesNotContain("| sh").doesNotContain("| bash").doesNotContain("eval ").doesNotContain("curl -s | ");
        assertThat(script).contains("https://github.com/goughliam1813/quartet-qe/releases/download/");
        // The version in the script is the plugin's version, and every platform has a pin line.
        assertThat(script).contains("VERSION=\"" + manifest().path("version").asText() + "\"");
        for (String platform : List.of("mac_arm64", "mac", "linux", "linux_arm64")) assertThat(script).contains("SHA256_" + platform + "=\"");
        // It parses.
        Process process = new ProcessBuilder("sh", "-n", PLUGIN.resolve("scripts/start-quartet.sh").toString()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(out).isZero();
    }

    @Test
    void theSkillAndCommandAreNamedAndDescribedForClaude() throws Exception {
        String skill = Files.readString(PLUGIN.resolve("skills/quartet-qe/SKILL.md"));
        assertThat(skill).startsWith("---\nname: quartet-qe\ndescription: ").contains("Never ask for a token, password or key in the chat");
        String command = Files.readString(PLUGIN.resolve("commands/test-my-site.md"));
        assertThat(command).startsWith("---\ndescription: ").contains("$ARGUMENTS");
    }

    @Test
    void theMarketplaceFilePointsAtThePluginFolder() throws Exception {
        JsonNode marketplace = Json.parse(Files.readString(REPO.resolve(".claude-plugin/marketplace.json")));
        assertThat(marketplace.path("plugins").get(0).path("name").asText()).isEqualTo(manifest().path("name").asText());
        assertThat(marketplace.path("plugins").get(0).path("source").asText()).isEqualTo("./plugin");
    }
}

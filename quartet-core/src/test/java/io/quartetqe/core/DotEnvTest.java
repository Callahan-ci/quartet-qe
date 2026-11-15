package io.quartetqe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvTest {
    @TempDir Path dir;

    @Test
    void savesASecretKeepsTheRestAndReplacesAnOldValue() throws Exception {
        Path file = dir.resolve(".env");
        DotEnv.set(file, "JIRA_TOKEN", "first");
        DotEnv.set(file, "OTHER", "kept");
        DotEnv.set(file, "JIRA_TOKEN", "second");

        assertThat(DotEnv.read(file)).containsEntry("JIRA_TOKEN", "second").containsEntry("OTHER", "kept").hasSize(2);
        assertThat(Files.readString(file)).doesNotContain("first");
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }
    }

    @Test
    void readIgnoresCommentsAndQuotesAndAMissingFile() throws Exception {
        assertThat(DotEnv.read(dir.resolve("none"))).isEmpty();
        Files.writeString(dir.resolve(".env"), "# note\nA=\"quoted\"\nB='single'\n\nC=plain\n");
        assertThat(DotEnv.read(dir.resolve(".env"))).containsEntry("A", "quoted").containsEntry("B", "single").containsEntry("C", "plain");
    }

    @Test
    void badNamesAndValuesWithLineBreaksAreRefused() {
        assertThatThrownBy(() -> DotEnv.set(dir.resolve(".env"), "lower", "x")).hasMessageContaining("capital letters");
        assertThatThrownBy(() -> DotEnv.set(dir.resolve(".env"), "A B", "x")).hasMessageContaining("capital letters");
        assertThatThrownBy(() -> DotEnv.set(dir.resolve(".env"), "OK", "a\nB=injected")).hasMessageContaining("line break");
    }

    @Test
    void theRealEnvironmentWinsOverTheFile() throws Exception {
        Files.writeString(dir.resolve(".env"), "PATH=from-file\nONLY_IN_FILE=yes\n");
        var merged = DotEnv.merged(dir);
        assertThat(merged.get("PATH")).isNotEqualTo("from-file");
        assertThat(merged).containsEntry("ONLY_IN_FILE", "yes");
    }

    @Test
    void aSettingLeftEmptyOrUnfilledByALauncherCountsAsNotGiven() {
        assertThat(DotEnv.blankOrUnfilled("")).isTrue();
        assertThat(DotEnv.blankOrUnfilled("   ")).isTrue();
        assertThat(DotEnv.blankOrUnfilled(null)).isTrue();
        assertThat(DotEnv.blankOrUnfilled("${user_config.jira_url}")).isTrue();
        assertThat(DotEnv.blankOrUnfilled("https://your-company.atlassian.net")).isFalse();
        assertThat(DotEnv.blankOrUnfilled("a-token-with-$dollar")).isFalse();
    }
}

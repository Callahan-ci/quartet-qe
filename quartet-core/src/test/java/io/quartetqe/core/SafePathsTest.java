package io.quartetqe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafePathsTest {
    @TempDir Path temp;

    private Path folder() throws Exception {
        Path root = temp.resolve("repo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {}");
        return root;
    }

    @Test
    void anOrdinaryFileInsideIsReadable() throws Exception {
        assertThat(SafePaths.readable(folder(), "src/App.java")).exists();
    }

    @Test
    void aWayOutByDotsOrAnAbsolutePathIsRefused() throws Exception {
        Path root = folder();
        assertThatThrownBy(() -> SafePaths.readable(root, "../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePaths.readable(root, "src/../../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePaths.readable(root, "/etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePaths.writable(root, "../x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSymbolicLinkThatLeadsOutIsNotFollowedForReadsOrWrites() throws Exception {
        Path root = folder();
        Path outside = temp.resolve("secret-notes.txt");
        Files.writeString(outside, "private");
        Files.createSymbolicLink(root.resolve("src/link.txt"), outside);
        Files.createSymbolicLink(root.resolve("linked-dir"), temp);
        assertThatThrownBy(() -> SafePaths.readable(root, "src/link.txt")).hasMessageContaining("outside the folder");
        assertThatThrownBy(() -> SafePaths.readable(root, "linked-dir/secret-notes.txt")).hasMessageContaining("outside the folder");
        assertThatThrownBy(() -> SafePaths.writable(root, "src/link.txt")).hasMessageContaining("symbolic link");
        assertThatThrownBy(() -> SafePaths.writable(root, "linked-dir/new.txt")).hasMessageContaining("outside the folder");
        assertThat(Files.readString(outside)).isEqualTo("private");
    }

    @Test
    void aLinkInsideThatPointsAtACredentialFileIsStillRefused() throws Exception {
        Path root = folder();
        Files.writeString(root.resolve(".env"), "TOKEN=abc");
        Files.createSymbolicLink(root.resolve("notes.md"), root.resolve(".env"));
        assertThatThrownBy(() -> SafePaths.readable(root, "notes.md")).hasMessageContaining("credential");
    }

    @Test
    void credentialLookingFilesAreNotShown() {
        for (String name : new String[] {".env", ".env.local", "config/.ssh/id_rsa", "keys/server.pem", ".git/config", "home/.aws/credentials", "id_ed25519", "cert.p12", ".npmrc"}) {
            assertThat(SafePaths.looksSecret(Path.of(name))).as(name).isTrue();
        }
        for (String name : new String[] {"README.md", "src/Env.java", "docs/credentials-howto.md", ".github/workflows/ci.yml", "environment.yaml"}) {
            assertThat(SafePaths.looksSecret(Path.of(name))).as(name).isFalse();
        }
    }

    @Test
    void aNewFileInsideAndOverAnOrdinaryOneMayBeWritten() throws Exception {
        Path root = folder();
        assertThat(SafePaths.writable(root, "src/New.java")).isEqualTo(root.resolve("src/New.java").toAbsolutePath().normalize());
        assertThat(SafePaths.writable(root, "deeper/still/New.java")).isNotNull();
        assertThat(SafePaths.writable(root, "src/App.java")).isNotNull();
    }

    @Test
    void aTestRunSeesTheBasicsAndWhatTheEnvironmentFileNamesButNotOtherTokens() throws Exception {
        Path file = temp.resolve("env.yaml");
        Files.writeString(file, "targets:\n  db:\n    password: \"${DB_PASSWORD}\"\n    url: ${DB_URL:-jdbc:x}\n");
        Map<String, String> child = new java.util.HashMap<>();
        for (String name : new String[] {"PATH", "HOME", "JAVA_HOME", "GRADLE_USER_HOME", "ANDROID_HOME", "QUARTET_MODE", "AI_API_KEY", "DB_PASSWORD",
                "JIRA_TOKEN", "GITHUB_TOKEN", "AWS_SECRET_ACCESS_KEY", "SSH_AUTH_SOCK", "MY_OWN"}) child.put(name, "x");
        ChildEnvironment.restrict(child, Set.of(), ChildEnvironment.referencedBy(file), Map.of());
        assertThat(child).containsKeys("PATH", "HOME", "JAVA_HOME", "GRADLE_USER_HOME", "ANDROID_HOME", "QUARTET_MODE", "AI_API_KEY", "DB_PASSWORD");
        assertThat(child).doesNotContainKeys("JIRA_TOKEN", "GITHUB_TOKEN", "AWS_SECRET_ACCESS_KEY", "SSH_AUTH_SOCK", "MY_OWN");
    }

    @Test
    void theFilterCanBePassedMoreNamesOrSwitchedOff() {
        Map<String, String> child = new java.util.HashMap<>(Map.of("PATH", "/bin", "MY_OWN", "1", "OTHER", "2"));
        ChildEnvironment.restrict(child, Set.of(), Set.of(), Map.of("QUARTET_PASS_ENV", "MY_OWN, X"));
        assertThat(child).containsKey("MY_OWN").doesNotContainKey("OTHER");
        Map<String, String> all = new java.util.HashMap<>(Map.of("PATH", "/bin", "OTHER", "2"));
        ChildEnvironment.restrict(all, Set.of(), Set.of(), Map.of("QUARTET_INHERIT_ENV", "true"));
        assertThat(all).containsKey("OTHER");
        assertThat(ChildEnvironment.mayReachTests("JIRA_TOKEN", Set.of(), Map.of())).isFalse();
        assertThat(ChildEnvironment.mayReachTests("DB_PASSWORD", Set.of("DB_PASSWORD"), Map.of())).isTrue();
    }
}

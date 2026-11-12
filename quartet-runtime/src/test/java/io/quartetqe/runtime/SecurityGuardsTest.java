package io.quartetqe.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quartetqe.core.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each of these was a real hole, found by running the attack against real systems: a "read-only" Postgres that accepted
 * CREATE and INSERT, a read-only MongoDB that a $out stage wrote through, and typed text that ran as a command on a device.
 */
class SecurityGuardsTest {
    @Test
    void aReadOnlyDatabaseTargetAllowsOnlyReadingStatements() {
        for (String sql : List.of("SELECT 1", "  select * from orders where id = ?", "WITH w AS (SELECT 1) SELECT * FROM w", "SHOW server_version",
                "EXPLAIN SELECT 1", "VALUES (1)", "TABLE orders", "/* why */ SELECT 1", "-- note\nSELECT 1", "(SELECT 1)")) {
            PostgresDriver.requireReadOnlyStatement(sql);
        }
        for (String sql : List.of("INSERT INTO t VALUES (1)", "CREATE TABLE t(x int)", "DROP TABLE t", "UPDATE t SET x = 1", "DELETE FROM t", "COPY (SELECT 1) TO PROGRAM 'id'",
                "SET default_transaction_read_only = off", "/* SELECT */ DELETE FROM t", "TRUNCATE t", "CALL p()", "DO $$ BEGIN END $$", "")) {
            assertThatThrownBy(() -> PostgresDriver.requireReadOnlyStatement(sql)).as(sql).hasMessageContaining("read-only").hasMessageContaining("allowWrite");
        }
    }

    @Test
    void aReadOnlyMongoTargetRefusesStagesThatWriteWhereverTheyAreNestedAndServerSideCodeAlways() {
        MongoDriver.requireSafe(Json.parse("[{\"$match\":{\"sku\":\"A\"}},{\"$limit\":1},{\"$lookup\":{\"from\":\"x\",\"pipeline\":[{\"$match\":{}}],\"as\":\"y\"}}]"), false);

        assertThatThrownBy(() -> MongoDriver.requireSafe(Json.parse("[{\"$limit\":1},{\"$out\":\"copy\"}]"), false)).hasMessageContaining("$out").hasMessageContaining("read-only");
        assertThatThrownBy(() -> MongoDriver.requireSafe(Json.parse("[{\"$merge\":{\"into\":\"copy\"}}]"), false)).hasMessageContaining("$merge");
        assertThatThrownBy(() -> MongoDriver.requireSafe(Json.parse("[{\"$facet\":{\"a\":[{\"$out\":\"copy\"}]}}]"), false)).as("nested").hasMessageContaining("$out");
        assertThatThrownBy(() -> MongoDriver.requireSafe(Json.parse("[{\"$lookup\":{\"pipeline\":[{\"$merge\":{\"into\":\"c\"}}]}}]"), false)).as("nested").hasMessageContaining("$merge");

        MongoDriver.requireSafe(Json.parse("[{\"$out\":\"copy\"}]"), true);
        for (String code : List.of("{\"$where\":\"sleep(1000)\"}", "[{\"$addFields\":{\"x\":{\"$function\":{\"body\":\"f\",\"args\":[],\"lang\":\"js\"}}}}]", "[{\"$group\":{\"_id\":1,\"v\":{\"$accumulator\":{}}}}]")) {
            assertThatThrownBy(() -> MongoDriver.requireSafe(Json.parse(code), true)).as("even on a test database: " + code).hasMessageContaining("runs code");
        }
    }

    private static AndroidBridge bridge(List<String> seen) {
        return new AndroidBridge("emulator-5554", command -> {
            seen.add(String.join(" ", command.subList(3, command.size())));
            return command.contains("resolve-activity") ? "x.y/.Main\n" : "";
        });
    }

    @Test
    void textTypedIntoAnAppIsQuotedForTheDeviceShellAndCannotCarryALineBreak() throws Exception {
        List<String> seen = new ArrayList<>();
        AndroidBridge bridge = bridge(seen);

        bridge.typeText("C 7; rm");
        bridge.typeText("it's $HOME `id` & more");

        assertThat(seen).containsExactly("shell input text 'C%s7;%srm'", "shell input text 'it'\\''s%s$HOME%s`id`%s&%smore'");
        for (String bad : List.of("hello\ntouch\t/sdcard/x", "a\rb", "tab\there", "nul\0byte", "bell\u0007")) {
            assertThatThrownBy(() -> bridge.typeText(bad)).as("control character in " + bad.length() + " chars").hasMessageContaining("control characters");
        }
        assertThat(seen).as("nothing was sent to the device for the refused text").hasSize(2);
    }

    @Test
    void anAppIdOrAddressThatIsNotWhatItClaimsToBeNeverReachesTheDeviceShell() {
        List<String> seen = new ArrayList<>();
        AndroidBridge bridge = bridge(seen);

        for (String bad : List.of("x; reboot", "org.example.app && id", "$(id)", "a b.c", "single", "org..example", "")) {
            assertThatThrownBy(() -> bridge.launchApp(bad)).as(bad).hasMessageContaining("not an Android package name");
        }
        assertThat(seen).isEmpty();

        assertThatThrownBy(() -> bridge.openUrl("javascript:alert(1)")).hasMessageContaining("http and https");
        assertThatThrownBy(() -> bridge.openUrl("file:///sdcard/secret")).hasMessageContaining("http and https");
        assertThat(AndroidBridge.shellQuote("a'b")).isEqualTo("'a'\\''b'");
    }
}

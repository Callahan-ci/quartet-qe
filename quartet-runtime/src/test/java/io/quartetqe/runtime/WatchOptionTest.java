package io.quartetqe.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WatchOptionTest {
    @Test
    void slowMotionIsOffUnlessAskedForAndIsKeptWithinSaneLimits() {
        assertThat(BrowserSurface.slowMoMs(Map.of())).isZero();
        assertThat(BrowserSurface.slowMoMs(Map.of("QUARTET_SLOWMO_MS", " "))).isZero();
        assertThat(BrowserSurface.slowMoMs(Map.of("QUARTET_SLOWMO_MS", "400"))).isEqualTo(400);
        assertThat(BrowserSurface.slowMoMs(Map.of("QUARTET_SLOWMO_MS", "-5"))).isZero();
        assertThat(BrowserSurface.slowMoMs(Map.of("QUARTET_SLOWMO_MS", "999999"))).isEqualTo(5000);
        assertThatThrownBy(() -> BrowserSurface.slowMoMs(Map.of("QUARTET_SLOWMO_MS", "slow"))).hasMessageContaining("number of milliseconds");
    }
}

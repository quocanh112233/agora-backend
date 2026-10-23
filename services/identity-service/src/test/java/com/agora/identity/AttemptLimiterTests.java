package com.agora.identity;

import com.agora.identity.application.AttemptLimiter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AttemptLimiterTests {
    @Test
    void blocksAfterLimitAndReopensAfterWindowWithoutSleeping() {
        var limiter = new AttemptLimiter(2, Duration.ofMinutes(15), 100);
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        assertThat(limiter.allow("client", now)).isTrue();
        assertThat(limiter.allow("client", now)).isTrue();
        assertThat(limiter.allow("client", now)).isFalse();
        assertThat(limiter.allow("other", now)).isTrue();
        assertThat(limiter.allow("client", now.plus(Duration.ofMinutes(15)))).isTrue();
    }

    @Test
    void failsClosedAtKeyCapacityAndReclaimsExpiredKeys() {
        var limiter = new AttemptLimiter(10, Duration.ofMinutes(15), 2);
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        assertThat(limiter.allow("first", now)).isTrue();
        assertThat(limiter.allow("second", now)).isTrue();
        assertThat(limiter.allow("third", now)).isFalse();
        assertThat(limiter.allow("third", now.plus(Duration.ofMinutes(15)))).isTrue();
    }
}

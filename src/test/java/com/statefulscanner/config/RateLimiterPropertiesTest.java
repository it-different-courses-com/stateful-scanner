package com.statefulscanner.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterPropertiesTest {

    @Test
    void constructor_atLowerBoundOnePermitPerSecond_isAccepted() {
        var props = new RateLimiterProperties(1.0, 0.0);

        assertThat(props.permitsPerSecond()).isEqualTo(1.0);
        assertThat(props.warmupPeriodSeconds()).isZero();
    }

    @Test
    void constructor_atUpperBoundTenThousandPermits_isAccepted() {
        var props = new RateLimiterProperties(10_000.0, 0.0);

        assertThat(props.permitsPerSecond()).isEqualTo(10_000.0);
    }

    @Test
    void constructor_typicalValues_areAccepted() {
        var props = new RateLimiterProperties(100.0, 2.5);

        assertThat(props.permitsPerSecond()).isEqualTo(100.0);
        assertThat(props.warmupPeriodSeconds()).isEqualTo(2.5);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.99, -1.0, -100.0, 10_000.01, 1_000_000.0})
    void constructor_permitsPerSecondOutOfRange_throwsIllegalArgumentException(double rate) {
        assertThatThrownBy(() -> new RateLimiterProperties(rate, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permits-per-second")
                .hasMessageContaining(String.valueOf(rate));
    }

    @Test
    void constructor_permitsPerSecondNaN_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new RateLimiterProperties(Double.NaN, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permits-per-second")
                .hasMessageContaining("finite");
    }

    @Test
    void constructor_permitsPerSecondPositiveInfinity_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new RateLimiterProperties(Double.POSITIVE_INFINITY, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permits-per-second")
                .hasMessageContaining("finite");
    }

    @Test
    void constructor_permitsPerSecondNegativeInfinity_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new RateLimiterProperties(Double.NEGATIVE_INFINITY, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permits-per-second")
                .hasMessageContaining("finite");
    }

    @Test
    void constructor_warmupZero_isAccepted() {
        var props = new RateLimiterProperties(100.0, 0.0);

        assertThat(props.warmupPeriodSeconds()).isZero();
    }

    @Test
    void constructor_warmupPositive_isAccepted() {
        var props = new RateLimiterProperties(100.0, 30.0);

        assertThat(props.warmupPeriodSeconds()).isEqualTo(30.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, -1.0, -1_000.0})
    void constructor_warmupNegative_throwsIllegalArgumentException(double warmup) {
        assertThatThrownBy(() -> new RateLimiterProperties(100.0, warmup))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warmup-period-seconds")
                .hasMessageContaining(String.valueOf(warmup));
    }

    @Test
    void constructor_warmupNaN_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new RateLimiterProperties(100.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warmup-period-seconds")
                .hasMessageContaining("finite");
    }

    @Test
    void constructor_warmupPositiveInfinity_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new RateLimiterProperties(100.0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warmup-period-seconds")
                .hasMessageContaining("finite");
    }

    @Test
    void boundsConstants_haveExpectedValues() {
        assertThat(RateLimiterProperties.MIN_PERMITS_PER_SECOND).isEqualTo(1.0);
        assertThat(RateLimiterProperties.MAX_PERMITS_PER_SECOND).isEqualTo(10_000.0);
    }
}


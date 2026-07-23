package com.address.address_system.address.coordinate.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class DeliveryCoordinatePropertiesTest {

    @Test
    void rejectsRawRetentionLongerThanThirtyDays() {
        assertThatThrownBy(() -> new DeliveryCoordinateProperties(
                new BigDecimal("100"),
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                Duration.ofDays(31),
                Duration.ofHours(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30 days");
    }
}

package com.address.address_system.common.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ApiSecurityPropertiesTest {

    @Test
    void rejectsShortProductionSecret() {
        assertThatThrownBy(() -> new ApiSecurityProperties(
                true,
                "delivery-system",
                "too-short",
                "address-operator",
                "abcdef0123456789abcdef0123456789",
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
                Duration.ofMinutes(10),
                65_536
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    void rejectsReplayRetentionShorterThanTimestampValidityWindow() {
        assertThatThrownBy(() -> new ApiSecurityProperties(
                true,
                "delivery-system",
                "0123456789abcdef0123456789abcdef",
                "address-operator",
                "abcdef0123456789abcdef0123456789",
                Duration.ofMinutes(5),
                Duration.ofMinutes(9),
                Duration.ofMinutes(10),
                65_536
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }
}

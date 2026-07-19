package com.address.address_system.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;

import com.address.address_system.common.security.ApiRateLimitProperties.Policy;
import com.address.address_system.common.security.ApiRateLimitRepository.Consumption;
import com.address.address_system.common.security.ApiRateLimitService.Decision;

import org.junit.jupiter.api.Test;

class ApiRateLimitServiceTest {

    private static final Policy SEARCH = new Policy(1000, BigDecimal.valueOf(100));
    private static final Policy TARGET = new Policy(500, BigDecimal.valueOf(50));
    private static final Policy DEFAULT = new Policy(200, BigDecimal.valueOf(20));

    private final ApiRateLimitRepository repository = mock(ApiRateLimitRepository.class);

    @Test
    void usesAddressSearchPolicyForSearchEndpoint() {
        ApiRateLimitService service = service(true);
        when(repository.consume("client-a", "ADDRESS_SEARCH", SEARCH))
                .thenReturn(new Consumption(true, new BigDecimal("998.75")));

        Decision decision = service.consume(
                "client-a",
                "GET",
                "/api/v1/addresses"
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.limit()).isEqualTo(1000);
        assertThat(decision.remaining()).isEqualTo(998);
    }

    @Test
    void keepsDeliveryTargetInSeparateBucket() {
        ApiRateLimitService service = service(true);
        when(repository.consume("client-a", "DELIVERY_TARGET", TARGET))
                .thenReturn(new Consumption(true, new BigDecimal("499")));

        service.consume("client-a", "POST", "/api/v1/delivery-targets");

        verify(repository).consume("client-a", "DELIVERY_TARGET", TARGET);
    }

    @Test
    void calculatesRetryAfterFromMissingTokenAmountAndRefillRate() {
        Policy slowPolicy = new Policy(10, new BigDecimal("0.25"));
        ApiRateLimitProperties properties = new ApiRateLimitProperties(
                true,
                SEARCH,
                TARGET,
                slowPolicy,
                Duration.ofHours(24),
                Duration.ofHours(1)
        );
        ApiRateLimitService service = new ApiRateLimitService(properties, repository);
        when(repository.consume("client-a", "DEFAULT_API", slowPolicy))
                .thenReturn(new Consumption(false, new BigDecimal("0.50")));

        Decision decision = service.consume("client-a", "POST", "/api/v1/future-api");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(2);
    }

    @Test
    void bypassesRepositoryWhenRateLimitIsDisabled() {
        ApiRateLimitService service = service(false);

        Decision decision = service.consume(
                "client-a",
                "GET",
                "/api/v1/addresses"
        );

        assertThat(decision.enabled()).isFalse();
        assertThat(decision.allowed()).isTrue();
        verifyNoInteractions(repository);
    }

    private ApiRateLimitService service(boolean enabled) {
        return new ApiRateLimitService(
                new ApiRateLimitProperties(
                        enabled,
                        enabled ? SEARCH : null,
                        enabled ? TARGET : null,
                        enabled ? DEFAULT : null,
                        Duration.ofHours(24),
                        Duration.ofHours(1)
                ),
                repository
        );
    }
}

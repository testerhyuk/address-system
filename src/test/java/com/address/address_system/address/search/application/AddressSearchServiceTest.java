package com.address.address_system.address.search.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.address.address_system.address.search.persistence.AddressSearchRepository;

import org.junit.jupiter.api.Test;

class AddressSearchServiceTest {

    private final AddressSearchRepository repository = mock(AddressSearchRepository.class);
    private final AddressSearchService service = new AddressSearchService(repository);

    @Test
    void normalizesWhitespaceAndEscapesLikeWildcards() {
        service.search(" 서울 특별시%_ ", 20);

        verify(repository).search("서울 특별시%_", "서울 특별시\\%\\_", 20);
    }

    @Test
    void rejectsQueryShorterThanTwoCharactersAfterNormalization() {
        assertThatThrownBy(() -> service.search(" 서 ", 20))
                .isInstanceOf(AddressSearchService.InvalidSearchQueryException.class);
    }

    @Test
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> service.search("   ", 20))
                .isInstanceOf(AddressSearchService.InvalidSearchQueryException.class);
    }

    @Test
    void rejectsUnitNumberFromSearchQuery() {
        assertThatThrownBy(() -> service.search("서울시 중구 세종대로 110 1203호", 20))
                .isInstanceOf(
                        AddressSearchService.DetailedAddressNotAllowedException.class
                );
    }
}

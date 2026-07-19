package com.address.address_system.address.search.application;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.address.address_system.address.search.model.AddressSearchResult;
import com.address.address_system.address.search.persistence.AddressSearchRepository;

import org.springframework.stereotype.Service;

@Service
public class AddressSearchService {

    private static final int MIN_NORMALIZED_QUERY_LENGTH = 2;
    private static final int MAX_QUERY_LENGTH = 200;
    private static final Pattern DETAIL_ADDRESS_PATTERN = Pattern.compile(
            ".*(?:[0-9]+호|[0-9]+층).*"
    );

    private final AddressSearchRepository repository;

    public AddressSearchService(AddressSearchRepository repository) {
        this.repository = repository;
    }

    public List<AddressSearchResult> search(String query, int limit) {
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
            throw new InvalidSearchQueryException();
        }
        String normalized = normalize(query);
        if (normalized.replace(" ", "").length() < MIN_NORMALIZED_QUERY_LENGTH) {
            throw new InvalidSearchQueryException();
        }
        if (DETAIL_ADDRESS_PATTERN.matcher(normalized).matches()) {
            throw new DetailedAddressNotAllowedException();
        }
        return repository.search(normalized, escapeLikePattern(normalized), limit);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public static class InvalidSearchQueryException extends RuntimeException {

        public InvalidSearchQueryException() {
            super("주소 검색어는 공백을 제외하고 2자 이상 200자 이하여야 합니다");
        }
    }

    public static class DetailedAddressNotAllowedException extends RuntimeException {

        public DetailedAddressNotAllowedException() {
            super("주소 검색에는 세대 호수와 층 정보를 전달할 수 없습니다");
        }
    }

    public static class UnsupportedSearchParameterException extends RuntimeException {

        public UnsupportedSearchParameterException() {
            super("주소 검색에는 query와 limit 파라미터만 허용됩니다");
        }
    }
}

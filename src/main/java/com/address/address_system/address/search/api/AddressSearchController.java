package com.address.address_system.address.search.api;

import java.util.List;
import java.util.Set;

import com.address.address_system.address.search.application.AddressSearchService;
import com.address.address_system.address.search.application.AddressSearchService.UnsupportedSearchParameterException;
import com.address.address_system.address.search.model.AddressSearchResult;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/addresses")
public class AddressSearchController {

    private static final Set<String> ALLOWED_PARAMETERS = Set.of("query", "limit");

    private final AddressSearchService service;

    public AddressSearchController(AddressSearchService service) {
        this.service = service;
    }

    @GetMapping
    public SearchResponse search(
            @RequestParam @NotBlank @Size(max = 200) String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            HttpServletRequest request
    ) {
        if (!ALLOWED_PARAMETERS.containsAll(request.getParameterMap().keySet())) {
            throw new UnsupportedSearchParameterException();
        }
        return new SearchResponse(service.search(query, limit));
    }

    public record SearchResponse(List<AddressSearchResult> items) {

        public SearchResponse {
            items = List.copyOf(items);
        }
    }
}

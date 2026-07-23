package com.address.address_system.address.geocoding.client;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.address.address_system.address.geocoding.config.NominatimProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class NominatimClient {

    private static final ParameterizedTypeReference<List<NominatimSearchResult>> SEARCH_RESULTS =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final NominatimProperties properties;

    @Autowired
    public NominatimClient(NominatimProperties properties) {
        this(createRestClient(RestClient.builder(), properties), properties);
    }

    NominatimClient(RestClient restClient, NominatimProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public Status readStatus() {
        Status status = execute(
                () -> restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/status")
                                .queryParam("format", "json")
                                .build())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(Status.class)
        );
        if (status == null
                || status.status() != 0
                || status.dataUpdated() == null
                || status.softwareVersion() == null
                || status.softwareVersion().isBlank()) {
            throw new NominatimClientException(
                    "INVALID_STATUS_RESPONSE",
                    false,
                    "Nominatim 상태 응답이 올바르지 않습니다",
                    null
            );
        }
        return status;
    }

    public List<NominatimSearchResult> search(SearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        List<NominatimSearchResult> results = execute(
                () -> restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/search")
                                .queryParam("q", query.freeFormAddress())
                                .queryParam("countrycodes", "kr")
                                .queryParam("accept-language", "ko")
                                .queryParam("layer", "address")
                                .queryParam("format", "jsonv2")
                                .queryParam("addressdetails", 1)
                                .queryParam("dedupe", 0)
                                .queryParam("limit", properties.maxResults())
                                .build())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(SEARCH_RESULTS)
        );
        return results == null ? List.of() : List.copyOf(results);
    }

    private <T> T execute(Request<T> request) {
        try {
            return request.execute();
        }
        catch (ResourceAccessException exception) {
            throw new NominatimClientException(
                    "CONNECTION_FAILED",
                    true,
                    "Nominatim에 연결할 수 없습니다",
                    exception
            );
        }
        catch (RestClientResponseException exception) {
            int statusCode = exception.getStatusCode().value();
            boolean retryable = statusCode == 429 || statusCode >= 500;
            throw new NominatimClientException(
                    "HTTP_" + statusCode,
                    retryable,
                    "Nominatim 요청이 실패했습니다. status=" + statusCode,
                    exception
            );
        }
        catch (RestClientException | IllegalArgumentException exception) {
            throw new NominatimClientException(
                    "INVALID_RESPONSE",
                    false,
                    "Nominatim 응답을 처리할 수 없습니다",
                    exception
            );
        }
    }

    private static RestClient createRestClient(
            RestClient.Builder builder,
            NominatimProperties properties
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "address-system/0.1")
                .build();
    }

    @FunctionalInterface
    private interface Request<T> {
        T execute();
    }

    public record SearchQuery(
            String sido,
            String sigungu,
            String roadName,
            String buildingNumber,
            String zipCode
    ) {

        public SearchQuery {
            requireText(sido, "sido");
            requireText(roadName, "roadName");
            requireText(buildingNumber, "buildingNumber");
        }

        String freeFormAddress() {
            if (sigungu == null || sigungu.isBlank()) {
                return String.join(" ", sido, roadName, buildingNumber);
            }
            return String.join(
                    " ", sido, sigungu, roadName, buildingNumber
            );
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(
            int status,
            @JsonProperty("data_updated") Instant dataUpdated,
            @JsonProperty("software_version") String softwareVersion
    ) {
    }
}

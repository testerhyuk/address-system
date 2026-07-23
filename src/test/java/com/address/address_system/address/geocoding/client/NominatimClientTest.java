package com.address.address_system.address.geocoding.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;

import com.address.address_system.address.geocoding.client.NominatimClient.SearchQuery;
import com.address.address_system.address.geocoding.config.NominatimProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NominatimClientTest {

    private MockRestServiceServer server;
    private NominatimClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8081");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NominatimClient(
                builder.build(),
                new NominatimProperties(
                        URI.create("http://localhost:8081"),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(5),
                        10
                )
        );
    }

    @Test
    void parsesStatusResponse() {
        server.expect(requestTo("http://localhost:8081/status?format=json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "status": 0,
                          "data_updated": "2026-07-18T19:43:04+00:00",
                          "software_version": "5.3.2"
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        NominatimClient.Status status = client.readStatus();

        assertThat(status.status()).isZero();
        assertThat(status.softwareVersion()).isEqualTo("5.3.2");
        server.verify();
    }

    @Test
    void sendsFreeFormKoreanAddressQueryAndParsesCandidates() {
        server.expect(requestTo(containsString("/search?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(requestTo(containsString("q=")))
                .andExpect(requestTo(containsString("countrycodes=kr")))
                .andExpect(requestTo(containsString("accept-language=ko")))
                .andExpect(requestTo(containsString("addressdetails=1")))
                .andRespond(withSuccess(
                        """
                        [{
                          "place_id": 1,
                          "osm_type": "way",
                          "osm_id": 198561926,
                          "category": "place",
                          "type": "house",
                          "place_rank": 30,
                          "display_name": "서울특별시청",
                          "lat": "37.5667893",
                          "lon": "126.9784204",
                          "address": {
                            "house_number": "110",
                            "road": "세종대로",
                            "borough": "중구",
                            "city": "서울특별시",
                            "postcode": "04524",
                            "country_code": "kr"
                          }
                        }]
                        """,
                        MediaType.APPLICATION_JSON
                ));

        var results = client.search(new SearchQuery(
                "서울특별시",
                "중구",
                "세종대로",
                "110",
                "04524"
        ));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).address().get("house_number")).isEqualTo("110");
        server.verify();
    }
}

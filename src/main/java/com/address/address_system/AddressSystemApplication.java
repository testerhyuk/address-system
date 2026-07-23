package com.address.address_system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.address.address_system.address.geocoding.config.NominatimProperties;
import com.address.address_system.common.security.ApiRateLimitProperties;
import com.address.address_system.common.security.ApiSecurityProperties;
import com.address.address_system.address.coordinate.config.DeliveryCoordinateProperties;
import com.address.address_system.address.coordinate.analysis.CoordinateAnalysisProperties;
import com.address.address_system.address.coordinate.quality.CoordinateQualityProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        NominatimProperties.class,
        ApiSecurityProperties.class,
        ApiRateLimitProperties.class,
        DeliveryCoordinateProperties.class,
        CoordinateAnalysisProperties.class,
        CoordinateQualityProperties.class
})
public class AddressSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(AddressSystemApplication.class, args);
	}

}

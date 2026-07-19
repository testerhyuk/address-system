package com.address.address_system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.address.address_system.address.geocoding.config.NominatimProperties;
import com.address.address_system.common.security.ApiRateLimitProperties;
import com.address.address_system.common.security.ApiSecurityProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        NominatimProperties.class,
        ApiSecurityProperties.class,
        ApiRateLimitProperties.class
})
public class AddressSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(AddressSystemApplication.class, args);
	}

}

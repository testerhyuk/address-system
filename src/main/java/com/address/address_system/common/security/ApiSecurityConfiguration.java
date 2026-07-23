package com.address.address_system.common.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ApiSecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            ApiSecurityProperties properties,
            ObjectProvider<HmacAuthenticationFilter> filterProvider,
            ApiAuthenticationFailureWriter failureWriter
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .logout(logout -> logout.disable())
                .requestCache(requestCache -> requestCache.disable())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        (request, response, exception) ->
                                failureWriter.unauthorized(response)
                ).accessDeniedHandler(
                        (request, response, exception) ->
                                failureWriter.forbidden(response)
                ));

        if (properties.enabled()) {
            http.authorizeHttpRequests(authorize -> authorize
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().denyAll()
            );
            http.addFilterBefore(
                    filterProvider.getObject(),
                    AnonymousAuthenticationFilter.class
            );
        }
        else {
            http.authorizeHttpRequests(authorize -> authorize
                    .anyRequest().permitAll()
            );
        }

        return http.build();
    }
}

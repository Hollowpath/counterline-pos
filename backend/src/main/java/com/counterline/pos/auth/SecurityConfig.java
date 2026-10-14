package com.counterline.pos.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Stateless bearer-token security. The SPA keeps the token and sends it in the Authorization
 * header; no cookies are used, so CSRF protection is not needed.
 */
@Configuration
@EnableMethodSecurity
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        // Route security failures through the MVC exception handlers so 401/403 bodies are
        // problem details like every other error.
        var errors = new ProblemDetailsSecurityErrorHandler(resolver);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/terminal-setup",
                                "/api/v1/auth/pin-lock").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/outlets", "/api/v1/outlets/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/display/connect",
                                "/api/v1/dashboard/auth/login").permitAll()
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // The web apps themselves (static files in the image): they log in through the API.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/display.html", "/dashboard.html",
                                "/assets/**", "/favicon.ico").permitAll()
                        // A customer display's token opens its own API and nothing else...
                        .requestMatchers("/api/v1/display/**").hasRole(TokenService.DISPLAY_ROLE)
                        // The dashboard: the catalogue is head office's; sales for head office and managers.
                        .requestMatchers("/api/v1/dashboard/catalog/**", "/api/v1/dashboard/discounts/**",
                                "/api/v1/dashboard/vouchers/**")
                                .hasRole(TokenService.ADMIN_ROLE)
                        .requestMatchers("/api/v1/dashboard/**").hasAnyRole(TokenService.ADMIN_ROLE, "MANAGER")
                        // ...and the rest of the API is for staff.
                        .anyRequest().access(SecurityConfig::staffOnly))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors));
        return http.build();
    }

    /** Head-office dashboard passwords (staff use PINs, see PinHasher). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecretKey jwtSigningKey(SecurityProperties properties) {
        return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Checks expiry/not-before and that we issued the token.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenService.ISSUER));
        return decoder;
    }

    /** Any authenticated session except a customer display's. */
    private static AuthorizationDecision staffOnly(Supplier<? extends Authentication> authentication,
                                                   RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        boolean staff = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream()
                .noneMatch(authority -> ("ROLE_" + TokenService.DISPLAY_ROLE).equals(authority.getAuthority()));
        return new AuthorizationDecision(staff);
    }

    /** Maps the token's "role" claim (e.g. MANAGER) to the authority ROLE_MANAGER. */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}

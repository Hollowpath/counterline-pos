package com.counterline.pos.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.CompositeHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import com.counterline.pos.auth.TokenService.Channel;
import org.springframework.security.oauth2.jwt.Jwt;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Stateless bearer-token security. The SPA keeps the token and sends it in the Authorization
 * header; no cookies are used, so CSRF protection is not needed.
 */
@Configuration
@EnableMethodSecurity
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
class SecurityConfig {

    /**
     * What the web apps load, and only from their own origin: scripts, styles, fonts, images, API calls.
     * No inline or eval'd script. Styles are not given 'unsafe-inline' either: React sets its style={...}
     * through the CSSOM, which a style-src of 'self' allows (only style attributes and style elements in
     * markup are refused, and the built pages have none). data: is for images alone: one CSS
     * background icon is an inline SVG.
     */
    static final String APP_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'none'";

    /**
     * Swagger UI's own paths (local only, see {@link #DOCS_PATHS}). Found by trying tighter policies against
     * it: scripts stay 'self' only; it needs one inline style element (its page carries one), which is
     * allowed for style elements alone, not for style attributes; and data: images (its icons).
     */
    static final String DOCS_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "style-src-elem 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    private static final String[] DOCS_PATHS = {"/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"};

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, StaffSessionGuard staffSessions,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
                                    @Value("${springdoc.swagger-ui.enabled:false}") boolean docsEnabled) throws Exception {
        // Route security failures through the MVC exception handlers so 401/403 bodies are
        // problem details like every other error.
        var errors = new ProblemDetailsSecurityErrorHandler(resolver);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        // No HSTS until the site is served over HTTPS: it is HTTPS-only by nature, and once a
                        // browser has seen it for a host it refuses plain HTTP there for its whole max-age.
                        .httpStrictTransportSecurity(HeadersConfigurer.HstsConfig::disable)
                        .addHeaderWriter(contentSecurityPolicy(docsEnabled)))
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
                        // A manager's approval at a till: one void or refund (ApprovalTokens), nothing else.
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/void", "/api/v1/orders/*/refund")
                                .access(allow(Set.of(Channel.TILL, Channel.APPROVAL)))
                        // The dashboard: the catalogue, outlets and staff are head office's; sales for head office and managers.
                        .requestMatchers("/api/v1/dashboard/catalog/**", "/api/v1/dashboard/discounts/**",
                                "/api/v1/dashboard/vouchers/**", "/api/v1/dashboard/admin/**")
                                .access(allow(Set.of(Channel.TILL, Channel.DASHBOARD), TokenService.ADMIN_ROLE))
                        .requestMatchers("/api/v1/dashboard/**")
                                .access(allow(Set.of(Channel.TILL, Channel.DASHBOARD), TokenService.ADMIN_ROLE, "MANAGER"))
                        // Customers are managed on the dashboard as well as found at the till.
                        .requestMatchers("/api/v1/customers", "/api/v1/customers/**")
                                .access(allow(Set.of(Channel.TILL, Channel.DASHBOARD)))
                        // ...and the rest of the API is the till's: not a dashboard session or an approval.
                        .anyRequest().access(allow(Set.of(Channel.TILL))))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(staffSessions)))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors));
        return http.build();
    }

    /** The strict policy everywhere; Swagger UI's paths get the relaxed one only while Swagger is enabled. */
    private static HeaderWriter contentSecurityPolicy(boolean docsEnabled) {
        if (!docsEnabled) {
            return new StaticHeadersWriter("Content-Security-Policy", APP_CSP);
        }
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher docs = new OrRequestMatcher(Arrays.stream(DOCS_PATHS).map(paths::matcher).toArray(RequestMatcher[]::new));
        return new CompositeHeaderWriter(List.of(
                new DelegatingRequestMatcherHeaderWriter(docs, new StaticHeadersWriter("Content-Security-Policy", DOCS_CSP)),
                new DelegatingRequestMatcherHeaderWriter(new NegatedRequestMatcher(docs),
                        new StaticHeadersWriter("Content-Security-Policy", APP_CSP))));
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

    /**
     * A signed-in session, not a customer display's, issued through one of {@code channels} (a token
     * without a channel is a till's) and, when {@code roles} are given, holding one of them.
     */
    private static AuthorizationManager<RequestAuthorizationContext> allow(Set<Channel> channels, String... roles) {
        return (authentication, context) -> {
            Authentication auth = authentication.get();
            boolean signedIn = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
            if (!signedIn || hasRole(auth, TokenService.DISPLAY_ROLE)) {
                return new AuthorizationDecision(false);
            }
            Object channel = auth.getPrincipal() instanceof Jwt jwt ? jwt.getClaim(TokenService.CHANNEL_CLAIM) : null;
            boolean allowed = channels.contains(Channel.of(channel))
                    && (roles.length == 0 || Arrays.stream(roles).anyMatch(role -> hasRole(auth, role)));
            return new AuthorizationDecision(allowed);
        };
    }

    private static boolean hasRole(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
    }

    /**
     * Maps the token's "role" claim (e.g. MANAGER) to the authority ROLE_MANAGER, and refuses a
     * manager's dashboard token once the manager or their outlet is no longer active.
     */
    private static JwtAuthenticationConverter jwtAuthenticationConverter(StaffSessionGuard staffSessions) {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            staffSessions.check(jwt);
            return authorities.convert(jwt);
        });
        return converter;
    }
}

package com.counterline.pos.auth;

import com.counterline.pos.auth.DashboardAuthService.DashboardLoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard/auth")
class DashboardAuthController {

    private final DashboardAuthService dashboardAuth;
    private final TokenRevocations tokenRevocations;

    DashboardAuthController(DashboardAuthService dashboardAuth, TokenRevocations tokenRevocations) {
        this.dashboardAuth = dashboardAuth;
        this.tokenRevocations = tokenRevocations;
    }

    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=******]";
        }
    }

    @Operation(summary = "Log in to the dashboard with email and password",
            description = "Head office sees every outlet; an outlet manager sees their own.")
    @PostMapping("/login")
    DashboardLoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return dashboardAuth.login(request.email(), request.password(), httpRequest.getRemoteAddr());
    }

    @Operation(summary = "Log out of the dashboard: this session's token stops working at once",
            description = "Only the token presented is ended; the same person's other sessions go on.",
            security = @SecurityRequirement(name = "bearer"))
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal Jwt jwt) {
        tokenRevocations.revoke(jwt);
    }
}

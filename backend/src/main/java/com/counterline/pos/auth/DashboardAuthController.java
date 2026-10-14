package com.counterline.pos.auth;

import com.counterline.pos.auth.DashboardAuthService.DashboardLoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard/auth")
class DashboardAuthController {

    private final DashboardAuthService dashboardAuth;

    DashboardAuthController(DashboardAuthService dashboardAuth) {
        this.dashboardAuth = dashboardAuth;
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
}

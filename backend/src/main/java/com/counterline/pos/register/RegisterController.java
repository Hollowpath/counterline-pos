package com.counterline.pos.register;

import com.counterline.pos.auth.Terminal;
import com.counterline.pos.register.RegisterDtos.CloseRequest;
import com.counterline.pos.register.RegisterDtos.MovementRequest;
import com.counterline.pos.register.RegisterDtos.RegisterStatus;
import com.counterline.pos.register.RegisterDtos.SessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/register")
@SecurityRequirement(name = "bearer")
class RegisterController {

    private final RegisterService register;

    RegisterController(RegisterService register) {
        this.register = register;
    }

    @Operation(summary = "Is the outlet's register open? With the current (or last closed) session and its sales")
    @GetMapping
    RegisterStatus status(@AuthenticationPrincipal Jwt jwt) {
        return register.status(Terminal.of(jwt).outletId());
    }

    @Operation(summary = "Open the register", description = "409 if it is already open.")
    @PostMapping("/open")
    @ResponseStatus(HttpStatus.CREATED)
    SessionResponse open(@AuthenticationPrincipal Jwt jwt) {
        return register.open(Terminal.of(jwt));
    }

    @Operation(summary = "Pay In / Out: money into or out of the register outside of sales")
    @PostMapping("/movements")
    SessionResponse recordMovement(@Valid @RequestBody MovementRequest request, @AuthenticationPrincipal Jwt jwt) {
        return register.recordMovement(request, Terminal.of(jwt));
    }

    @Operation(summary = "Close the register", description = "Send the session version shown; 409 if it changed since.")
    @PostMapping("/close")
    SessionResponse close(@Valid @RequestBody CloseRequest request, @AuthenticationPrincipal Jwt jwt) {
        return register.close(request, Terminal.of(jwt));
    }
}

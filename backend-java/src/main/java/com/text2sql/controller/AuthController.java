package com.text2sql.controller;

import com.text2sql.security.JwtService;
import com.text2sql.security.LoginUser;
import com.text2sql.util.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        var auth = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        LoginUser user = (LoginUser) auth.getPrincipal();
        return Map.of("token", jwtService.generate(user), "user", userPayload(user));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return userPayload(CurrentUser.get());
    }

    private Map<String, Object> userPayload(LoginUser user) {
        return Map.of("id", user.getId(), "username", user.getUsername(), "role", user.getRole(), "displayName", user.getDisplayName());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
}

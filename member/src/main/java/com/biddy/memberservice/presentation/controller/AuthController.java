package com.biddy.memberservice.presentation.controller;

import com.biddy.memberservice.application.dto.request.LoginRequest;
import com.biddy.memberservice.application.dto.request.SignupRequest;
import com.biddy.memberservice.application.dto.response.TokenResponse;


import com.biddy.memberservice.application.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request) {
        authService.signup(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/reissue")
    public ResponseEntity<TokenResponse> reissue(@RequestHeader("Refresh-Token") String refreshToken) {
        return ResponseEntity.ok(authService.reissue(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Long memberId,
                                        @RequestHeader("Authorization") String authorizationHeader) {
        String accessToken = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;
        authService.logout(memberId, accessToken);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/email/send")
    public ResponseEntity<Void> sendVerificationEmail(@RequestBody Map<String, String> body) {
        authService.sendVerificationEmail(body.get("email"));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/email/verify")
    public ResponseEntity<Void> verifyEmail(@RequestParam String email,
                                            @RequestParam String token) {
        authService.verifyEmail(email, token);
        return ResponseEntity.ok().build();
    }
}

package com.example.cx.core.auth;

import com.example.cx.core.config.CxProperties;
import com.example.cx.core.identity.IdentityDtos.LoginRequest;
import com.example.cx.core.identity.IdentityDtos.LoginResponse;
import com.example.cx.core.identity.IdentityDtos.RegisterOrganizationRequest;
import com.example.cx.core.identity.IdentityDtos.UserDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final boolean trustForwardedFor;

    public AuthController(AuthService auth, CxProperties props) {
        this.auth = auth;
        this.trustForwardedFor = props.security().trustForwardedFor();
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest r, HttpServletRequest req) {
        return auth.login(r, clientIp(req));
    }

    @PostMapping("/register-organization")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse register(@Valid @RequestBody RegisterOrganizationRequest r, HttpServletRequest req) {
        return auth.register(r, clientIp(req));
    }

    @GetMapping("/me")
    public UserDto me() { return auth.me(); }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() { auth.logout(); }

    /** X-Forwarded-For is only honoured when explicitly enabled (i.e. when the gateway is the only way in). */
    private String clientIp(HttpServletRequest req) {
        if (trustForwardedFor) {
            var fwd = AuthService.firstForwarded(req.getHeader("X-Forwarded-For"));
            if (fwd.isPresent()) return fwd.get();
        }
        return req.getRemoteAddr();
    }
}

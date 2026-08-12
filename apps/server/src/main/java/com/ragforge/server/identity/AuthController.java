package com.ragforge.server.identity;

import com.ragforge.server.config.SessionProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;
    private final SessionProperties sessionProperties;

    public AuthController(AuthService authService, SessionProperties sessionProperties) {
        this.authService = authService;
        this.sessionProperties = sessionProperties;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
                                                     HttpServletRequest servletRequest) {
        UserAccount user = authService.register(request.email(), request.password(), request.displayName(),
                servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(UserResponse.from(user)));
    }

    @PostMapping({"/auth/login", "/sessions"})
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest servletRequest) {
        AuthService.SessionData session = authService.login(request.email(), request.password(), servletRequest);
        ResponseCookie cookie = ResponseCookie.from(sessionProperties.getCookieName(), session.rawToken())
                .httpOnly(true)
                .secure(sessionProperties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(sessionProperties.getTtl())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header("X-CSRF-Token", session.csrfToken())
                .body(LoginResponse.from(session));
    }

    @GetMapping("/sessions/current")
    public CurrentSessionResponse current(Authentication authentication) {
        return CurrentSessionResponse.from((SessionPrincipal) authentication.getPrincipal());
    }

    @DeleteMapping("/sessions/current")
    public ResponseEntity<Void> logout(Authentication authentication, HttpServletRequest request,
                                       HttpServletResponse response) {
        SessionPrincipal principal = (SessionPrincipal) authentication.getPrincipal();
        authService.logout(principal, request);
        ResponseCookie cookie = ResponseCookie.from(sessionProperties.getCookieName(), "")
                .httpOnly(true)
                .secure(sessionProperties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    public record RegisterRequest(@NotBlank @Email String email,
                                  @NotBlank @Size(min = 12, max = 128) String password,
                                  @NotBlank @Size(min = 1, max = 120) String displayName) {
    }

    public record LoginRequest(@NotBlank @Email String email,
                               @NotBlank String password) {
    }

    public record UserResponse(UUID userId, String email, String displayName, String platformRole) {
        static UserResponse from(UserAccount user) {
            return new UserResponse(user.id(), user.email(), user.displayName(), user.platformRole());
        }
    }

    public record RegisterResponse(UserResponse user) {
    }

    public record SessionResponse(UUID sessionId, UUID userId, java.time.Instant expiresAt, String csrfToken) {
        static SessionResponse from(AuthService.SessionData session) {
            return new SessionResponse(session.sessionId(), session.user().id(), session.expiresAt(),
                    session.csrfToken());
        }

        static SessionResponse from(SessionPrincipal principal) {
            return new SessionResponse(principal.sessionId(), principal.userId(), principal.expiresAt(),
                    principal.csrfToken());
        }
    }

    public record LoginResponse(SessionResponse session, UserResponse user) {
        static LoginResponse from(AuthService.SessionData session) {
            return new LoginResponse(SessionResponse.from(session), UserResponse.from(session.user()));
        }
    }

    public record CurrentSessionResponse(SessionResponse session, UserResponse user) {
        static CurrentSessionResponse from(SessionPrincipal principal) {
            UserAccount user = new UserAccount(principal.userId(), principal.email(), principal.displayName(),
                    "", principal.platformRole());
            return new CurrentSessionResponse(SessionResponse.from(principal), UserResponse.from(user));
        }
    }
}

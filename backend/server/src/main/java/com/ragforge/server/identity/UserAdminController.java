package com.ragforge.server.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserAdminController {
    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    public UserPage list(Authentication authentication) {
        return new UserPage(service.list(principal(authentication)).stream().map(UserResponse::from).toList(), null);
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(Authentication authentication,
                                               @Valid @RequestBody CreateUserRequest request,
                                               HttpServletRequest servletRequest) {
        UserAccount user = service.create(principal(authentication), request.email(), request.displayName(),
                request.password(), servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PutMapping("/{userId}")
    public UserResponse update(Authentication authentication, @PathVariable UUID userId,
                               @Valid @RequestBody UpdateUserRequest request,
                               HttpServletRequest servletRequest) {
        return UserResponse.from(service.update(principal(authentication), userId, request.displayName(),
                request.platformRole(), request.status(), request.password(), servletRequest));
    }

    @DeleteMapping("/{userId}")
    public UserResponse disable(Authentication authentication, @PathVariable UUID userId,
                                HttpServletRequest servletRequest) {
        return UserResponse.from(service.disable(principal(authentication), userId, servletRequest));
    }

    private SessionPrincipal principal(Authentication authentication) {
        return (SessionPrincipal) authentication.getPrincipal();
    }

    public record CreateUserRequest(@NotBlank @Email String email,
                                    @NotBlank @Size(min = 1, max = 120) String displayName,
                                    @NotBlank @Size(min = 12, max = 128) String password) {
    }

    public record UpdateUserRequest(@NotBlank @Size(min = 1, max = 120) String displayName,
                                    @NotBlank String platformRole,
                                    @NotBlank String status,
                                    @Size(min = 12, max = 128) String password) {
    }

    public record UserResponse(UUID userId, String email, String displayName, String platformRole,
                               String status, Instant createdAt, Instant updatedAt) {
        static UserResponse from(UserAccount user) {
            return new UserResponse(user.id(), user.email(), user.displayName(), user.platformRole(), user.status(),
                    user.createdAt(), user.updatedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UserPage(List<UserResponse> items, String nextCursor) {
    }
}

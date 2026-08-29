package com.ragforge.server.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bootstrap/platform-admin")
public class PlatformAdminBootstrapController {
    static final String TOKEN_HEADER = "X-RAGForge-Bootstrap-Token";

    private final PlatformAdminBootstrapService service;

    public PlatformAdminBootstrapController(PlatformAdminBootstrapService service) {
        this.service = service;
    }

    @GetMapping
    public PlatformAdminBootstrapService.BootstrapStatus status() {
        return service.status();
    }

    @PostMapping
    public ResponseEntity<BootstrapResponse> bootstrap(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody BootstrapRequest request, HttpServletRequest servletRequest) {
        PlatformAdminBootstrapService.BootstrapResult result = service.bootstrap(token, request.email(),
                request.password(), request.displayName(), servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(BootstrapResponse.from(result));
    }

    public record BootstrapRequest(@NotBlank @Email @Size(max = 320) String email,
                                   @NotBlank @Size(min = 12, max = 128) String password,
                                   @NotBlank @Size(min = 1, max = 120) String displayName) {
    }

    public record BootstrapResponse(UUID userId, String email, String displayName, String platformRole,
                                    String status, String mode) {
        static BootstrapResponse from(PlatformAdminBootstrapService.BootstrapResult result) {
            UserAccount user = result.user();
            return new BootstrapResponse(user.id(), user.email(), user.displayName(), user.platformRole(),
                    user.status(), result.mode());
        }
    }
}

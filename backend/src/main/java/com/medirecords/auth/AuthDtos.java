package com.medirecords.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request and response bodies of the auth API. Tokens never appear here: they travel in HttpOnly cookies. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 50) String username,
            @NotBlank @Size(max = 100) String password) {}

    public record CurrentUser(String username, String fullName, List<Role> roles) {}
}

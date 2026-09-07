package com.tpross.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record CreateUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 128) String password
) {
    public CreateUserRequest {
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public String toString() {
        return "CreateUserRequest[email=" + email + ", password=<redacted>]";
    }
}

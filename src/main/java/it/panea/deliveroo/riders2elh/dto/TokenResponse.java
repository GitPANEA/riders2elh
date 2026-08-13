package it.panea.deliveroo.riders2elh.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Risposta di /oauth2/token, formato standard OAuth2 (snake_case, non camelCase italiano). */
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        String scope
) {}

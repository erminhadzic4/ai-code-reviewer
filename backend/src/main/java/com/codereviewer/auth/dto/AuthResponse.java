package com.codereviewer.auth.dto;

/**
 * @param expiresIn lifetime of the access token in seconds
 */
public record AuthResponse(String accessToken, long expiresIn, UserResponse user) {
}
